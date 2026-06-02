package dev.doctor4t.wathe.api.bed;

import dev.doctor4t.wathe.block_entity.TrimmedBedBlockEntity;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 床附加效果注册表。
 *
 * <p>扩展职业模组可以在初始化时把自己的“塞床物品”注册进来，
 * wathe 的床系统会统一负责：
 * 1. 识别该物品能否放入床；
 * 2. 把效果与归属者同步到床头方块实体；
 * 3. 在睡觉后的统一结算时回调对应处理器；
 * 4. 给回放保留 bed_effect 字段，供扩展模组按效果格式化文本。</p>
 */
public final class BedEffectRegistry {
    private static final Map<Identifier, BedEffectHandler> BY_EFFECT_ID = new HashMap<>();
    private static final Map<Item, BedEffectHandler> BY_ADDITIVE_ITEM = new IdentityHashMap<>();

    private BedEffectRegistry() {
    }

    public static void register(BedEffectHandler handler) {
        BY_EFFECT_ID.put(handler.effectId(), handler);
        BY_ADDITIVE_ITEM.put(handler.additiveItem(), handler);
    }

    public static @Nullable BedEffectHandler getByEffectId(Identifier effectId) {
        return BY_EFFECT_ID.get(effectId);
    }

    public static @Nullable BedEffectHandler getByAdditiveItem(Item item) {
        return BY_ADDITIVE_ITEM.get(item);
    }

    /**
     * TrimmedBedBlock 的统一入口。
     *
     * <p>如果主手拿着的是某个已注册的床效果物品，
     * 就交给对应处理器决定能否放置并执行逻辑。</p>
     */
    public static boolean tryApplyHeldEffect(ServerPlayerEntity player, TrimmedBedBlockEntity bed, BlockPos pos) {
        ItemStack heldStack = player.getMainHandStack();
        BedEffectHandler handler = getByAdditiveItem(heldStack.getItem());
        if (handler == null || !handler.canApply(bed, player, heldStack)) {
            return false;
        }
        handler.applyToBed(bed, player, heldStack, pos);
        return true;
    }

    /**
     * 供扩展模组复用的“标准塞床”实现。
     */
    public static boolean applyStandardEffect(
            ServerPlayerEntity player,
            ItemStack heldStack,
            TrimmedBedBlockEntity bed,
            BlockPos pos,
            Identifier effectId,
            boolean replaceExistingEffect
    ) {
        return applyStandardEffect(player, heldStack, bed, pos, effectId, replaceExistingEffect, null);
    }

    /**
     * 带自定义回放附加字段的标准塞床实现。
     */
    public static boolean applyStandardEffect(
            ServerPlayerEntity player,
            ItemStack heldStack,
            TrimmedBedBlockEntity bed,
            BlockPos pos,
            Identifier effectId,
            boolean replaceExistingEffect,
            @Nullable NbtCompound replayExtra
    ) {
        if (!replaceExistingEffect && bed.getBedEffect() != null) {
            return false;
        }

        Identifier additiveItemId = Registries.ITEM.getId(heldStack.getItem());
        String additiveItemName = Text.Serialization.toJsonString(heldStack.getName(), player.getRegistryManager());

        bed.setBedEffect(effectId.toString(), player.getUuidAsString());

        heldStack.decrement(1);
        player.playSoundToPlayer(SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.5f, 1f);

        NbtCompound extra = replayExtra == null ? new NbtCompound() : replayExtra.copy();
        extra.putString("bed_effect", effectId.toString());
        extra.putUuid("bed_effect_owner", player.getUuid());
        extra.putString("item_name", additiveItemName);
        GameRecordManager.putBlockPos(extra, "pos", bed.getPos());
        GameRecordManager.recordItemUse(player, additiveItemId, null, extra);
        return true;
    }

    /**
     * 玩家睡觉后 40 tick 的统一结算入口。
     */
    public static void triggerBedEffect(ServerPlayerEntity player) {
        TrimmedBedBlockEntity bed = findTriggeredBedEffect(player.getWorld(), player.getBlockPos());
        if (bed == null) {
            return;
        }

        Identifier effectId = Identifier.tryParse(bed.getBedEffect());
        if (effectId == null) {
            return;
        }

        BedEffectHandler handler = getByEffectId(effectId);
        if (handler == null) {
            return;
        }

        UUID owner = parseOwnerUuid(bed.getBedEffectOwner());
        if (handler.onSleepTrigger(player, bed, owner)) {
            bed.clearBedEffect();
        }
    }

    /**
     * 查找玩家当前睡眠结算会命中的那张“带效果的床”。
     *
     * <p>这个公开方法主要给扩展职业模组复用，
     * 例如机器人想在真正触发前拦截蝎子床免疫，就可以直接走这套与本体一致的搜索逻辑。</p>
     */
    public static @Nullable TrimmedBedBlockEntity findTriggeredBedEffect(World world, BlockPos centerPos) {
        return findTriggeredBed(world, centerPos);
    }

    /**
     * 把任意一半床坐标解析成床头方块实体。
     *
     * <p>这样无论玩家点的是床头还是床尾，后续都统一只操作床头保存的效果状态。</p>
     */
    public static @Nullable TrimmedBedBlockEntity resolveHead(World world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof TrimmedBedBlockEntity entity)) {
            return null;
        }

        BlockState state = world.getBlockState(pos);
        BedPart part = state.get(BedBlock.PART);
        Direction facing = state.get(HorizontalFacingBlock.FACING);

        if (part == BedPart.HEAD) {
            return entity;
        }

        BlockPos headPos = pos.offset(facing);
        if (world.getBlockEntity(headPos) instanceof TrimmedBedBlockEntity headEntity
                && world.getBlockState(headPos).get(BedBlock.PART) == BedPart.HEAD) {
            return headEntity;
        }

        return null;
    }

    private static @Nullable TrimmedBedBlockEntity findTriggeredBed(World world, BlockPos centerPos) {
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = centerPos.add(dx, dy, dz);
                    TrimmedBedBlockEntity entity = resolveHead(world, pos);
                    if (entity == null || entity.getBedEffect() == null) {
                        continue;
                    }

                    if (isLineClear(world, centerPos, pos)) {
                        return entity;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable UUID parseOwnerUuid(@Nullable String rawOwner) {
        if (rawOwner == null) {
            return null;
        }
        try {
            return UUID.fromString(rawOwner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 与旧版蝎子床判定保持一致，用简单的 3D Bresenham 判断中间是否被非床方块挡住。
     */
    private static boolean isLineClear(World world, BlockPos start, BlockPos end) {
        int x0 = start.getX();
        int y0 = start.getY();
        int z0 = start.getZ();
        int x1 = end.getX();
        int y1 = end.getY();
        int z1 = end.getZ();

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err1;
        int err2;

        int ax = 2 * dx;
        int ay = 2 * dy;
        int az = 2 * dz;

        if (dx >= dy && dx >= dz) {
            err1 = ay - dx;
            err2 = az - dx;
            while (x0 != x1) {
                x0 += sx;
                if (err1 > 0) {
                    y0 += sy;
                    err1 -= 2 * dx;
                }
                if (err2 > 0) {
                    z0 += sz;
                    err2 -= 2 * dx;
                }
                err1 += ay;
                err2 += az;

                if (isBlocking(world, new BlockPos(x0, y0, z0))) {
                    return false;
                }
            }
        } else if (dy >= dx && dy >= dz) {
            err1 = ax - dy;
            err2 = az - dy;
            while (y0 != y1) {
                y0 += sy;
                if (err1 > 0) {
                    x0 += sx;
                    err1 -= 2 * dy;
                }
                if (err2 > 0) {
                    z0 += sz;
                    err2 -= 2 * dy;
                }
                err1 += ax;
                err2 += az;

                if (isBlocking(world, new BlockPos(x0, y0, z0))) {
                    return false;
                }
            }
        } else {
            err1 = ay - dz;
            err2 = ax - dz;
            while (z0 != z1) {
                z0 += sz;
                if (err1 > 0) {
                    y0 += sy;
                    err1 -= 2 * dz;
                }
                if (err2 > 0) {
                    x0 += sx;
                    err2 -= 2 * dz;
                }
                err1 += ay;
                err2 += ax;

                if (isBlocking(world, new BlockPos(x0, y0, z0))) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 只要不是床，就视为挡住了“相邻床也会中招”的判定路径。
     */
    private static boolean isBlocking(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !(state.getBlock() instanceof BedBlock);
    }
}
