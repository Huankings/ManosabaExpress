package dev.doctor4t.wathe.game;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.MapVariablesWorldComponent;
import dev.doctor4t.wathe.entity.FirecrackerEntity;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.index.WatheEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.ComponentMap;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Clearable;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渐进式地图重置任务。
 * 该任务不会像原版一次性重置那样在单个 tick 内复制整张地图，
 * 而是把地图拆成多个小块，分多 tick 逐步恢复。
 *
 * <p>整个任务都在服务端主线程执行：
 * <ul>
 *     <li>先根据模板区域预计算若干个重置分块；</li>
 *     <li>每个 tick 只处理少量分块，降低卡顿风险；</li>
 *     <li>玩家留在大厅等待，并通过 ActionBar 看到进度；</li>
 *     <li>全部完成后再做一次收尾清理，并触发完成回调。</li>
 * </ul>
 */
public class MapResetTask {

    /**
     * 每个分块期望处理的方块数量。
     * 实际分块尺寸会根据模板区域体积自动换算。
     */
    private static final int TARGET_BLOCKS_PER_CHUNK = 5000;

    /**
     * 每个 tick 处理的分块数量。
     * 维持为 1 会让重置稍慢，但更不容易造成瞬时卡顿。
     */
    private static final int CHUNKS_PER_TICK = 1;

    /**
     * ActionBar 进度提示的刷新间隔。
     */
    private static final int PROGRESS_UPDATE_INTERVAL = 10;

    /**
     * 所有分块完成后额外执行的清理 tick 数。
     * 用于清掉方块替换过程中延迟产生的掉落物。
     */
    private static final int POST_CLEANUP_TICKS = 5;

    private final ServerWorld serverWorld;
    private final List<BlockBox> resetChunks;
    private final BlockPos offsetBlockPos;
    private final BlockBox backupTrainBox;
    private final int totalChunks;
    private final Runnable onComplete;

    private int currentChunkIndex = 0;
    private int tickCount = 0;
    private boolean finished = false;
    private int postCleanupTicksRemaining = -1;

    /**
     * 为当前地图创建一个新的渐进式重置任务。
     *
     * @param serverWorld 同时包含模板区域和实际游玩区域的世界
     * @param onComplete 重置和收尾清理全部结束后执行的回调
     */
    public MapResetTask(ServerWorld serverWorld, Runnable onComplete) {
        this.serverWorld = serverWorld;
        this.onComplete = onComplete;

        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);
        BlockPos backupMinPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMinPos());
        BlockPos backupMaxPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMaxPos());
        this.backupTrainBox = BlockBox.create(backupMinPos, backupMaxPos);

        BlockPos trainMinPos = BlockPos.ofFloored(
                areas.getResetTemplateArea().offset(Vec3d.of(areas.getResetPasteOffset())).getMinPos()
        );
        BlockPos trainMaxPos = trainMinPos.add(backupTrainBox.getDimensions());
        BlockBox trainBox = BlockBox.create(trainMinPos, trainMaxPos);

        this.offsetBlockPos = new BlockPos(
                trainBox.getMinX() - backupTrainBox.getMinX(),
                trainBox.getMinY() - backupTrainBox.getMinY(),
                trainBox.getMinZ() - backupTrainBox.getMinZ()
        );

        this.resetChunks = buildChunks(backupTrainBox, TARGET_BLOCKS_PER_CHUNK);
        this.totalChunks = resetChunks.size();

        // 渐进式重置发生在玩家正式传送进对局前，
        // 因此不能依赖玩家位置来维持模板区和目标区的区块加载。
        forceLoadRegion(serverWorld, backupMinPos, backupMaxPos);
        forceLoadRegion(serverWorld, trainMinPos, trainMaxPos);

        Wathe.LOGGER.info(
                "Started gradual map reset with {} chunk batches in {}.",
                totalChunks,
                serverWorld.getRegistryKey().getValue()
        );
    }

    /**
     * 推进一次渐进式重置。
     *
     * @return 如果整个任务已经彻底完成则返回 {@code true}
     */
    public boolean tick() {
        if (finished) {
            return true;
        }

        tickCount++;

        if (postCleanupTicksRemaining >= 0) {
            clearDroppedItems();
            postCleanupTicksRemaining--;
            if (postCleanupTicksRemaining < 0) {
                onFinished();
                return true;
            }
            return false;
        }

        for (int i = 0; i < CHUNKS_PER_TICK && currentChunkIndex < totalChunks; i++, currentChunkIndex++) {
            BlockBox chunk = resetChunks.get(currentChunkIndex);
            copyChunk(serverWorld, chunk, offsetBlockPos);
        }

        clearDroppedItems();

        if (tickCount % PROGRESS_UPDATE_INTERVAL == 1 || currentChunkIndex >= totalChunks) {
            broadcastProgress();
        }

        if (currentChunkIndex >= totalChunks) {
            postCleanupTicksRemaining = POST_CLEANUP_TICKS;
        }

        return false;
    }

    /**
     * 获取当前重置进度百分比，范围为 0 到 100。
     */
    public int getProgressPercent() {
        if (totalChunks == 0) {
            return 100;
        }
        return (int) ((currentChunkIndex / (float) totalChunks) * 100.0F);
    }

    /**
     * 返回当前重置任务是否已经完全结束。
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * 立即强制加载指定区域内的所有区块。
     */
    private static void forceLoadRegion(ServerWorld world, BlockPos minPos, BlockPos maxPos) {
        int minChunkX = minPos.getX() >> 4;
        int minChunkZ = minPos.getZ() >> 4;
        int maxChunkX = maxPos.getX() >> 4;
        int maxChunkZ = maxPos.getZ() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
    }

    /**
     * 清理由方块替换产生的掉落物实体。
     */
    private void clearDroppedItems() {
        for (ItemEntity item : serverWorld.getEntitiesByType(EntityType.ITEM, entity -> true)) {
            item.discard();
        }
    }

    /**
     * 向当前世界中的所有在线玩家显示重置进度。
     */
    private void broadcastProgress() {
        int percent = getProgressPercent();
        Text progressText = Text.literal("地图重置中 " + percent + "%").formatted(Formatting.YELLOW);

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.sendMessage(progressText, true);
        }

        Wathe.LOGGER.info(
                "Gradual map reset progress: {}/{} ({}%).",
                currentChunkIndex,
                totalChunks,
                percent
        );
    }

    /**
     * 完成重置收尾，清理实体并执行完成回调。
     */
    private void onFinished() {
        finished = true;

        serverWorld.getBlockTickScheduler().scheduleTicks(
                serverWorld.getBlockTickScheduler(),
                backupTrainBox,
                offsetBlockPos
        );

        for (PlayerBodyEntity body : serverWorld.getEntitiesByType(WatheEntities.PLAYER_BODY, entity -> true)) {
            body.discard();
        }
        for (ItemEntity item : serverWorld.getEntitiesByType(EntityType.ITEM, entity -> true)) {
            item.discard();
        }
        for (FirecrackerEntity entity : serverWorld.getEntitiesByType(WatheEntities.FIRECRACKER, firecracker -> true)) {
            entity.discard();
        }
        for (NoteEntity entity : serverWorld.getEntitiesByType(WatheEntities.NOTE, note -> true)) {
            entity.discard();
        }

        Text finishedText = Text.literal("地图重置中 100%").formatted(Formatting.GREEN);
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.sendMessage(finishedText, true);
        }

        Wathe.LOGGER.info("Gradual map reset finished in {}.", serverWorld.getRegistryKey().getValue());

        if (onComplete != null) {
            onComplete.run();
        }
    }

    /**
     * 把一个较大的模板区域拆分成多个更小的分块。
     *
     * <p>Y 轴采用从上到下的顺序遍历，能更安全地恢复受重力影响的方块。</p>
     */
    private static List<BlockBox> buildChunks(BlockBox box, int targetBlocks) {
        List<BlockBox> chunks = new ArrayList<>();

        int xLength = box.getMaxX() - box.getMinX() + 1;
        int yLength = box.getMaxY() - box.getMinY() + 1;
        int zLength = box.getMaxZ() - box.getMinZ() + 1;

        double scale = Math.cbrt((double) targetBlocks / ((double) xLength * yLength * zLength));
        int chunkX = Math.max(1, Math.min(xLength, (int) Math.ceil(xLength * scale)));
        int chunkY = Math.max(1, Math.min(yLength, (int) Math.ceil(yLength * scale)));
        int chunkZ = Math.max(1, Math.min(zLength, (int) Math.ceil(zLength * scale)));

        for (int y = box.getMaxY(); y >= box.getMinY(); y -= chunkY) {
            int yMin = Math.max(box.getMinY(), y - chunkY + 1);
            for (int x = box.getMinX(); x <= box.getMaxX(); x += chunkX) {
                int xMax = Math.min(box.getMaxX(), x + chunkX - 1);
                for (int z = box.getMinZ(); z <= box.getMaxZ(); z += chunkZ) {
                    int zMax = Math.min(box.getMaxZ(), z + chunkZ - 1);
                    chunks.add(BlockBox.create(
                            new BlockPos(x, yMin, z),
                            new BlockPos(xMax, y, zMax)
                    ));
                }
            }
        }

        return chunks;
    }

    /**
     * 把一个分块内的所有方块从模板区复制到实际游玩区。
     *
     * <p>这里刻意使用 {@link Block#FORCE_STATE}，
     * 因为渐进式模式可能把门、床这类多方块结构拆到不同分块里。
     * 如果继续沿用一次性重置时的通知式写入，更容易在中途触发邻居更新顺序问题。</p>
     */
    private static void copyChunk(ServerWorld world, BlockBox chunk, BlockPos offset) {
        List<Map.Entry<BlockPos, BlockEntitySnapshot>> pendingBlockEntities = new ArrayList<>();

        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos srcPos = new BlockPos(x, y, z);
                    BlockPos dstPos = srcPos.add(offset);
                    if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                        continue;
                    }

                    BlockEntity srcBlockEntity = world.getBlockEntity(srcPos);
                    if (srcBlockEntity != null) {
                        NbtCompound nbt = srcBlockEntity.createComponentlessNbt(world.getRegistryManager());
                        ComponentMap components = srcBlockEntity.getComponents();
                        pendingBlockEntities.add(new AbstractMap.SimpleEntry<>(
                                dstPos,
                                new BlockEntitySnapshot(nbt, components)
                        ));
                    }
                }
            }
        }

        // 先从上到下清空目标区域，给受重力影响或多方块结构提供更安全的写入基础。
        for (int y = chunk.getMaxY(); y >= chunk.getMinY(); y--) {
            for (int x = chunk.getMaxX(); x >= chunk.getMinX(); x--) {
                for (int z = chunk.getMaxZ(); z >= chunk.getMinZ(); z--) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offset);
                    if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                        continue;
                    }

                    BlockEntity blockEntity = world.getBlockEntity(dstPos);
                    Clearable.clear(blockEntity);
                    try {
                        world.setBlockState(dstPos, Blocks.BARRIER.getDefaultState(), Block.FORCE_STATE);
                    } catch (Exception ignored) {
                        // 某些多方块结构在中间态下仍可能抛出异常。
                        // 这里继续执行即可，后续的正式写入会把最终状态恢复回来。
                    }
                }
            }
        }

        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos srcPos = new BlockPos(x, y, z);
                    BlockPos dstPos = srcPos.add(offset);
                    if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(srcPos);
                    try {
                        world.setBlockState(dstPos, state, Block.FORCE_STATE);
                    } catch (Exception ignored) {
                        // 依赖相邻方块的多方块结构，在相邻分块尚未复制时可能暂时失败。
                        // 这里忽略异常，可以让整个渐进式重置流程更稳。
                    }
                }
            }
        }

        for (Map.Entry<BlockPos, BlockEntitySnapshot> entry : pendingBlockEntities) {
            BlockPos dstPos = entry.getKey();
            if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                continue;
            }

            BlockEntitySnapshot snapshot = entry.getValue();
            BlockEntity dstBlockEntity = world.getBlockEntity(dstPos);
            if (dstBlockEntity != null) {
                dstBlockEntity.readComponentlessNbt(snapshot.nbt(), world.getRegistryManager());
                dstBlockEntity.setComponents(snapshot.components());
                dstBlockEntity.markDirty();
            }
        }

        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offset);
                    if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(dstPos);
                    world.updateNeighbors(dstPos, state.getBlock());
                }
            }
        }

        // 渐进式复制阶段使用 FORCE_STATE 会优先保证服务端状态稳定，
        // 但它不会像原版一次性重置那样自然把所有最终状态完整推送给客户端。
        // 这里在分块复制完成后再做一轮显式同步，确保玩家客户端看到的方块外观、
        // 碰撞和可交互状态都与模板区域当前的最终结果一致。
        syncChunkToClients(world, chunk, offset);
    }

    /**
     * 用于保存 BlockEntity 恢复所需的数据快照。
     */
    private record BlockEntitySnapshot(NbtCompound nbt, ComponentMap components) {
    }

    /**
     * 把一个已经复制完成的分块最终状态显式同步给客户端。
     *
     * <p>这里使用 {@code oldState == newState} 的方式调用
     * {@link ServerWorld#updateListeners(BlockPos, BlockState, BlockState, int)}，
     * 目的是强制客户端重新接收当前位置的最新状态，而不是再次修改服务端方块。
     * 这样既能刷新玩家视角中的方块显示，也能触发对应 BlockEntity 的同步数据包。</p>
     */
    private static void syncChunkToClients(ServerWorld world, BlockBox chunk, BlockPos offset) {
        for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) {
            for (int x = chunk.getMinX(); x <= chunk.getMaxX(); x++) {
                for (int z = chunk.getMinZ(); z <= chunk.getMaxZ(); z++) {
                    BlockPos dstPos = new BlockPos(x, y, z).add(offset);
                    if (isTargetPositionOutsideBuildHeight(world, dstPos)) {
                        continue;
                    }

                    BlockState finalState = world.getBlockState(dstPos);
                    world.updateListeners(dstPos, finalState, finalState, Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    /**
     * 判断渐进式重置映射后的目标位置是否超出了世界可用高度。
     *
     * <p>这层保护主要用于兼容地图配置里的特殊偏移。
     * {@link ServerWorld#setBlockState(BlockPos, BlockState, int)} 在越界时通常只会写入失败，
     * 但 {@link ServerWorld#updateListeners(BlockPos, BlockState, BlockState, int)} 会继续尝试按区段索引同步。
     * 一旦目标 Y 超出世界高度，服务端内部就可能拿到负数区段索引并直接崩溃。
     * 因此渐进式重置后续所有访问目标区域的位置，都必须先经过这里的高度校验。</p>
     */
    private static boolean isTargetPositionOutsideBuildHeight(ServerWorld world, BlockPos pos) {
        return world.isOutOfHeightLimit(pos.getY());
    }
}
