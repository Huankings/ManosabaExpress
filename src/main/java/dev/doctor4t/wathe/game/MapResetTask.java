package dev.doctor4t.wathe.game;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.MapVariablesWorldComponent;
import dev.doctor4t.wathe.entity.FirecrackerEntity;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.index.WatheEntities;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
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
 * 该任务不会像原版一次性重置那样在单个 tick 内复制整张地图，而是把工作拆到多个 tick。
 *
 * <p>它支持两条可调试的执行路径：</p>
 * <ul>
 *     <li>完整渐进重置：沿用旧行为，把模板区域拆成多个小块后逐块完整复制；</li>
 *     <li>差异渐进重置：先分批比较整张地图，扫描结束后只恢复状态或方块实体数据不同的位置。</li>
 * </ul>
 *
 * <p>整个任务都在服务端主线程执行。玩家会留在大厅等待，通过 ActionBar 看到检查、恢复、
 * 同步三个阶段的进度；全部完成后再统一清理临时实体并触发开局回调。</p>
 */
public class MapResetTask {

    /**
     * 差异模式每个 tick 最多检查的方块数量。
     * 扫描只读取方块状态，绝大多数位置不会读取方块实体 NBT，因此可以明显高于写入阶段的批量。
     */
    private static final int MAX_SCAN_BLOCKS_PER_TICK = 20_000;

    /**
     * 差异扫描在单个服务端 tick 内使用的软时间预算。
     * 数量上限防止高性能机器一次扫描过多，时间上限则保护配置较低或装有较多扩展的服务器。
     */
    private static final long SCAN_TIME_BUDGET_NANOS = 3_000_000L;

    /**
     * 扫描期间每处理这些方块才读取一次时钟，避免每格调用 nanoTime 反而增加扫描成本。
     */
    private static final int SCAN_TIME_CHECK_INTERVAL = 256;

    /**
     * 差异模式每个 tick 最多恢复的坐标数量。
     * 恢复包含清空、写入、方块实体恢复、邻居更新和客户端同步，成本远高于单纯比较。
     */
    private static final int MAX_CHANGED_BLOCKS_PER_TICK = 1_000;

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
    private final long totalBlocks;
    private final boolean differentialResetEnabled;
    private final Runnable onComplete;

    /**
     * 差异坐标使用 BlockPos 的 long 压缩形式保存。
     * 大地图被大量修改时，这比为每个位置长期保留一个 BlockPos 对象更节省内存。
     */
    private final LongArrayList changedSourcePositions = new LongArrayList();

    private int currentChunkIndex = 0;
    private int tickCount = 0;
    private boolean finished = false;
    private int postCleanupTicksRemaining = -1;
    private ResetPhase phase;
    private int scanX;
    private int scanY;
    private int scanZ;
    private long scannedBlocks = 0L;
    private int currentChangedIndex = 0;
    private int currentFinalizeIndex = 0;
    private int rebuiltBlockCount = 0;
    private int blockEntityOnlyCount = 0;
    private boolean progressMilestonePending = true;
    private final long startedAtNanos = System.nanoTime();
    private long scanFinishedAtNanos = -1L;

    /**
     * 为当前地图创建一个新的渐进式重置任务。
     *
     * @param serverWorld 同时包含模板区域和实际游玩区域的世界
     * @param onComplete 重置和收尾清理全部结束后执行的回调
     */
    public MapResetTask(ServerWorld serverWorld, Runnable onComplete) {
        this(serverWorld, true, onComplete);
    }

    /**
     * 为当前地图创建一个新的渐进式重置任务。
     *
     * @param serverWorld 同时包含模板区域和实际游玩区域的世界
     * @param differentialResetEnabled 是否先完整扫描并只恢复出现差异的位置
     * @param onComplete 重置和收尾清理全部结束后执行的回调
     */
    public MapResetTask(ServerWorld serverWorld, boolean differentialResetEnabled, Runnable onComplete) {
        this.serverWorld = serverWorld;
        this.differentialResetEnabled = differentialResetEnabled;
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
        this.totalBlocks = (long) (backupTrainBox.getMaxX() - backupTrainBox.getMinX() + 1)
                * (backupTrainBox.getMaxY() - backupTrainBox.getMinY() + 1)
                * (backupTrainBox.getMaxZ() - backupTrainBox.getMinZ() + 1);
        this.scanX = backupTrainBox.getMinX();
        this.scanY = backupTrainBox.getMaxY();
        this.scanZ = backupTrainBox.getMinZ();
        this.phase = differentialResetEnabled ? ResetPhase.SCANNING : ResetPhase.FULL_RESET;

        // 渐进式重置发生在玩家正式传送进对局前，
        // 因此不能依赖玩家位置来维持模板区和目标区的区块加载。
        forceLoadRegion(serverWorld, backupMinPos, backupMaxPos);
        forceLoadRegion(serverWorld, trainMinPos, trainMaxPos);

        if (differentialResetEnabled) {
            Wathe.LOGGER.info(
                    "Started differential gradual map reset scanning {} blocks in {}.",
                    totalBlocks,
                    serverWorld.getRegistryKey().getValue()
            );
        } else {
            Wathe.LOGGER.info(
                    "Started full gradual map reset with {} chunk batches in {}.",
                    totalChunks,
                    serverWorld.getRegistryKey().getValue()
            );
        }
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

        if (differentialResetEnabled) {
            tickDifferentialReset();
        } else {
            tickFullReset();
        }

        clearDroppedItems();

        if (tickCount % PROGRESS_UPDATE_INTERVAL == 1 || progressMilestonePending) {
            broadcastProgress();
            progressMilestonePending = false;
        }

        return false;
    }

    /**
     * 推进旧版完整渐进重置。
     * 差异开关关闭时仍完整保留这条路径，便于管理员对比效果或在特殊地图上临时回退。
     */
    private void tickFullReset() {
        for (int i = 0; i < CHUNKS_PER_TICK && currentChunkIndex < totalChunks; i++, currentChunkIndex++) {
            BlockBox chunk = resetChunks.get(currentChunkIndex);
            copyChunk(serverWorld, chunk, offsetBlockPos);
        }

        if (currentChunkIndex >= totalChunks) {
            beginPostCleanup();
        }
    }

    /**
     * 推进“先扫描、后恢复”的差异重置状态机。
     * 扫描完成之前绝不写入游玩区域，避免前面恢复的方块反过来影响后续比较结果。
     */
    private void tickDifferentialReset() {
        if (phase == ResetPhase.SCANNING) {
            scanDifferences();
        } else if (phase == ResetPhase.RESTORING) {
            restoreChangedBatch();
        } else if (phase == ResetPhase.FINALIZING) {
            finalizeChangedBatch();
        }
    }

    /**
     * 在一个受数量和时间双重限制的批次内比较模板区与游玩区。
     * 普通方块只比较 BlockState；只有 BlockState 相同且该方块确实支持方块实体时，
     * 才进一步序列化完整 NBT，从而把大地图最常见的扫描路径保持得尽量轻量。
     */
    private void scanDifferences() {
        long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NANOS;
        int scannedThisTick = 0;

        while (phase == ResetPhase.SCANNING && scannedThisTick < MAX_SCAN_BLOCKS_PER_TICK) {
            if (scannedThisTick > 0
                    && scannedThisTick % SCAN_TIME_CHECK_INTERVAL == 0
                    && System.nanoTime() >= deadline) {
                break;
            }

            BlockPos srcPos = new BlockPos(scanX, scanY, scanZ);
            BlockPos dstPos = srcPos.add(offsetBlockPos);
            if (!isTargetPositionOutsideBuildHeight(serverWorld, dstPos)
                    && positionsDiffer(serverWorld, srcPos, dstPos)) {
                changedSourcePositions.add(srcPos.asLong());
            }

            scannedBlocks++;
            scannedThisTick++;
            advanceScanCursor();
        }
    }

    /**
     * 推进扫描坐标。扫描顺序保持 Y 轴从上到下，与旧渐进重置处理多方块结构的顺序一致。
     */
    private void advanceScanCursor() {
        scanZ++;
        if (scanZ <= backupTrainBox.getMaxZ()) {
            return;
        }

        scanZ = backupTrainBox.getMinZ();
        scanX++;
        if (scanX <= backupTrainBox.getMaxX()) {
            return;
        }

        scanX = backupTrainBox.getMinX();
        scanY--;
        if (scanY < backupTrainBox.getMinY()) {
            scanFinishedAtNanos = System.nanoTime();
            phase = changedSourcePositions.isEmpty() ? ResetPhase.POST_CLEANUP : ResetPhase.RESTORING;
            progressMilestonePending = true;

            Wathe.LOGGER.info(
                    "Differential map scan finished in {} ms: {} scanned, {} changed in {}.",
                    nanosToMillis(scanFinishedAtNanos - startedAtNanos),
                    scannedBlocks,
                    changedSourcePositions.size(),
                    serverWorld.getRegistryKey().getValue()
            );

            if (changedSourcePositions.isEmpty()) {
                beginPostCleanup();
            }
        }
    }

    /**
     * 判断一个模板位置和对应游玩位置是否存在需要恢复的差异。
     *
     * <p>{@link BlockEntity#createNbt} 会同时编码方块实体自己的持久化 NBT 和 1.21 的
     * Data Components，但不会写入 x/y/z 坐标，因此可直接比较两个不同坐标上的内容。
     * 这一步可以识别箱子物品、告示牌文字、Wathe 托盘内容、门的内部状态等变化。</p>
     */
    private static boolean positionsDiffer(ServerWorld world, BlockPos srcPos, BlockPos dstPos) {
        BlockState srcState = world.getBlockState(srcPos);
        BlockState dstState = world.getBlockState(dstPos);
        if (!srcState.equals(dstState)) {
            return true;
        }

        if (!srcState.hasBlockEntity()) {
            return false;
        }

        BlockEntity srcBlockEntity = world.getBlockEntity(srcPos);
        BlockEntity dstBlockEntity = world.getBlockEntity(dstPos);
        if (srcBlockEntity == null || dstBlockEntity == null) {
            return srcBlockEntity != dstBlockEntity;
        }
        if (srcBlockEntity.getType() != dstBlockEntity.getType()) {
            return true;
        }

        return !srcBlockEntity.createNbt(world.getRegistryManager())
                .equals(dstBlockEntity.createNbt(world.getRegistryManager()));
    }

    /**
     * 只恢复扫描阶段记录的差异位置。
     *
     * <p>每批会先完整取得模板快照，再统一清空需要重建的目标方块，随后按反向顺序写回。
     * 这样既不会在同一批处理中读到自己刚写入的目标状态，也尽量维持旧实现对重力方块、
     * 门和床等多方块结构采用的安全写入顺序。</p>
     */
    private void restoreChangedBatch() {
        int endIndex = Math.min(currentChangedIndex + MAX_CHANGED_BLOCKS_PER_TICK, changedSourcePositions.size());
        List<RestoreEntry> entries = new ArrayList<>(endIndex - currentChangedIndex);

        for (int index = currentChangedIndex; index < endIndex; index++) {
            BlockPos srcPos = BlockPos.fromLong(changedSourcePositions.getLong(index));
            BlockPos dstPos = srcPos.add(offsetBlockPos);
            if (isTargetPositionOutsideBuildHeight(serverWorld, dstPos)) {
                continue;
            }

            BlockState templateState = serverWorld.getBlockState(srcPos);
            BlockEntity srcBlockEntity = serverWorld.getBlockEntity(srcPos);
            TypedBlockEntitySnapshot snapshot = srcBlockEntity == null ? null : new TypedBlockEntitySnapshot(
                    srcBlockEntity.getType(),
                    srcBlockEntity.createComponentlessNbt(serverWorld.getRegistryManager()),
                    srcBlockEntity.getComponents()
            );

            BlockState currentState = serverWorld.getBlockState(dstPos);
            BlockEntity currentBlockEntity = serverWorld.getBlockEntity(dstPos);
            boolean rebuildBlock = !templateState.equals(currentState)
                    || (snapshot == null) != (currentBlockEntity == null)
                    || (snapshot != null
                    && currentBlockEntity != null
                    && snapshot.type() != currentBlockEntity.getType());
            entries.add(new RestoreEntry(dstPos, templateState, snapshot, rebuildBlock));
        }

        // 只有方块/方块实体结构不同的位置才需要经过屏障重建。
        // 单纯是箱子内容、告示牌文字或 tray 数据变化时，直接恢复 NBT 可避免无意义的方块替换。
        for (RestoreEntry entry : entries) {
            if (!entry.rebuildBlock()) {
                continue;
            }

            Clearable.clear(serverWorld.getBlockEntity(entry.destinationPos()));
            try {
                serverWorld.setBlockState(entry.destinationPos(), Blocks.BARRIER.getDefaultState(), Block.FORCE_STATE);
            } catch (Exception ignored) {
                // 某些多方块结构在中间态下仍可能抛出异常。
                // FORCE_STATE 已尽量避免邻居连锁更新，后续正式写入仍会继续恢复最终状态。
            }
        }

        for (int index = entries.size() - 1; index >= 0; index--) {
            RestoreEntry entry = entries.get(index);
            if (!entry.rebuildBlock()) {
                continue;
            }

            try {
                serverWorld.setBlockState(entry.destinationPos(), entry.state(), Block.FORCE_STATE);
            } catch (Exception ignored) {
                // 与旧渐进重置一致：单个多方块结构的临时写入失败不能中断整张地图的恢复。
            }
            rebuiltBlockCount++;
        }

        for (RestoreEntry entry : entries) {
            TypedBlockEntitySnapshot snapshot = entry.blockEntitySnapshot();
            if (snapshot != null) {
                BlockEntity dstBlockEntity = serverWorld.getBlockEntity(entry.destinationPos());
                if (dstBlockEntity != null && dstBlockEntity.getType() == snapshot.type()) {
                    dstBlockEntity.readComponentlessNbt(snapshot.nbt(), serverWorld.getRegistryManager());
                    dstBlockEntity.setComponents(snapshot.components());
                    dstBlockEntity.markDirty();
                }
            }

            if (!entry.rebuildBlock()) {
                blockEntityOnlyCount++;
            }
        }

        currentChangedIndex = endIndex;
        if (currentChangedIndex >= changedSourcePositions.size()) {
            /*
             * 所有差异位置写入完毕后才能统一更新邻居。
             * 如果门、床等多方块结构恰好跨越两个恢复批次，逐批更新邻居会让先写入的一半
             * 在另一半尚未恢复时被原版结构校验破坏，最终留下只恢复一半的地图。
             */
            phase = ResetPhase.FINALIZING;
            progressMilestonePending = true;
        }
    }

    /**
     * 在全部差异方块及其方块实体数据恢复完毕后，分批执行邻居更新和客户端同步。
     * 该阶段依然只遍历差异坐标，不会重新触碰与模板一致的普通方块。
     */
    private void finalizeChangedBatch() {
        int endIndex = Math.min(currentFinalizeIndex + MAX_CHANGED_BLOCKS_PER_TICK, changedSourcePositions.size());

        // 先完成当前批次的服务端邻居更新，再统一发客户端状态，避免客户端收到结构的中间态。
        for (int index = currentFinalizeIndex; index < endIndex; index++) {
            BlockPos srcPos = BlockPos.fromLong(changedSourcePositions.getLong(index));
            BlockPos dstPos = srcPos.add(offsetBlockPos);
            if (isTargetPositionOutsideBuildHeight(serverWorld, dstPos)) {
                continue;
            }

            BlockState finalState = serverWorld.getBlockState(dstPos);
            serverWorld.updateNeighbors(dstPos, finalState.getBlock());
        }

        for (int index = currentFinalizeIndex; index < endIndex; index++) {
            BlockPos srcPos = BlockPos.fromLong(changedSourcePositions.getLong(index));
            BlockPos dstPos = srcPos.add(offsetBlockPos);
            if (isTargetPositionOutsideBuildHeight(serverWorld, dstPos)) {
                continue;
            }

            // FORCE_STATE 不保证客户端收到最终外观和方块实体更新，因此所有差异位置都显式同步一次。
            BlockState finalState = serverWorld.getBlockState(dstPos);
            serverWorld.updateListeners(dstPos, finalState, finalState, Block.NOTIFY_LISTENERS);
        }

        currentFinalizeIndex = endIndex;
        if (currentFinalizeIndex >= changedSourcePositions.size()) {
            beginPostCleanup();
        }
    }

    /**
     * 进入统一收尾阶段。完整渐进模式和差异模式共用掉落物延迟清理与最终实体清理。
     */
    private void beginPostCleanup() {
        phase = ResetPhase.POST_CLEANUP;
        if (postCleanupTicksRemaining < 0) {
            postCleanupTicksRemaining = POST_CLEANUP_TICKS;
            progressMilestonePending = true;
        }
    }

    /**
     * 获取当前重置进度百分比，范围为 0 到 100。
     */
    public int getProgressPercent() {
        if (differentialResetEnabled) {
            if (phase == ResetPhase.SCANNING) {
                return totalBlocks == 0L ? 100 : (int) ((scannedBlocks * 100L) / totalBlocks);
            }
            if (phase == ResetPhase.RESTORING) {
                int changedCount = changedSourcePositions.size();
                return changedCount == 0 ? 100 : (int) ((currentChangedIndex * 100L) / changedCount);
            }
            if (phase == ResetPhase.FINALIZING) {
                int changedCount = changedSourcePositions.size();
                return changedCount == 0 ? 100 : (int) ((currentFinalizeIndex * 100L) / changedCount);
            }
            return 100;
        }

        return totalChunks == 0 ? 100 : (int) ((currentChunkIndex * 100L) / totalChunks);
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
        String label = differentialResetEnabled && phase == ResetPhase.SCANNING
                ? "地图检查中 "
                : differentialResetEnabled && phase == ResetPhase.FINALIZING ? "地图同步中 " : "地图重置中 ";
        Text progressText = Text.literal(label + percent + "%").formatted(Formatting.YELLOW);

        if (differentialResetEnabled && phase == ResetPhase.RESTORING && currentChangedIndex == 0) {
            progressText = Text.literal("地图检查完成，发现 " + changedSourcePositions.size() + " 处差异")
                    .formatted(Formatting.YELLOW);
        } else if (differentialResetEnabled && phase == ResetPhase.POST_CLEANUP && changedSourcePositions.isEmpty()) {
            progressText = Text.literal("地图检查完成，未发现差异").formatted(Formatting.GREEN);
        }

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.sendMessage(progressText, true);
        }

        if (differentialResetEnabled) {
            Wathe.LOGGER.info(
                    "Differential map reset phase {} progress: {}% ({} changed blocks found).",
                    phase,
                    percent,
                    changedSourcePositions.size()
            );
        } else {
            Wathe.LOGGER.info(
                    "Full gradual map reset progress: {}/{} ({}%).",
                    currentChunkIndex,
                    totalChunks,
                    percent
            );
        }
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

        long elapsedMillis = nanosToMillis(System.nanoTime() - startedAtNanos);
        if (differentialResetEnabled) {
            Wathe.LOGGER.info(
                    "Differential gradual map reset finished in {}: {} scanned, {} changed, {} rebuilt, "
                            + "{} block-entity-only, scan {} ms, total {} ms.",
                    serverWorld.getRegistryKey().getValue(),
                    scannedBlocks,
                    changedSourcePositions.size(),
                    rebuiltBlockCount,
                    blockEntityOnlyCount,
                    scanFinishedAtNanos < 0L ? 0L : nanosToMillis(scanFinishedAtNanos - startedAtNanos),
                    elapsedMillis
            );
        } else {
            Wathe.LOGGER.info(
                    "Full gradual map reset finished in {} after {} ms.",
                    serverWorld.getRegistryKey().getValue(),
                    elapsedMillis
            );
        }

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
     * 差异恢复阶段除了数据本身还需要保存类型，用于确认目标方块实体可安全接收模板 NBT。
     */
    private record TypedBlockEntitySnapshot(BlockEntityType<?> type, NbtCompound nbt, ComponentMap components) {
    }

    /**
     * 单个差异位置在当前恢复批次中的模板快照。
     */
    private record RestoreEntry(
            BlockPos destinationPos,
            BlockState state,
            TypedBlockEntitySnapshot blockEntitySnapshot,
            boolean rebuildBlock
    ) {
    }

    private enum ResetPhase {
        FULL_RESET,
        SCANNING,
        RESTORING,
        FINALIZING,
        POST_CLEANUP
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
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
