package dev.doctor4t.wathe.api.door;

import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.block_entity.DoorBlockEntity;
import dev.doctor4t.wathe.game.GameConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wathe 小门的服务端状态事务入口。
 *
 * <p>地图中的双门实际由两个独立方块实体组成，但在玩法上必须视为同一个门组。这里统一处理
 * 开关、撬坏、锁门和修复，避免扩展只修改其中一侧，或者分别对两侧执行 {@code open = !open}
 * 后把“一开一关”的错误状态永久保留下来。</p>
 */
public final class DoorApi {
    private DoorApi() {
    }

    /** 修复后立即关闭，适合工具箱、巫妖这类“恢复为正常关闭门”的能力。 */
    public enum RepairMode {
        CLOSE,
        /** 修复后保持打开，并重新开始 Wathe 默认的五秒自动关门倒计时。 */
        OPEN_THEN_AUTOCLOSE
    }

    /**
     * 一次门组操作的结果。旧状态用于扩展决定音效和回放内容，applied 用于判断是否应进入冷却。
     */
    public record OperationResult(
            boolean applied,
            boolean changed,
            int affectedDoors,
            boolean wasBlasted,
            boolean wasJammed
    ) {
    }

    /** 门组当前聚合状态；任意一侧拥有状态，就按整个门组拥有该状态处理。 */
    public record GroupState(int doorCount, boolean blasted, boolean jammed) {
        public boolean needsRepair() {
            return this.blasted || this.jammed;
        }
    }

    /**
     * 从玩家点击的上半格、下半格或门方块实体位置解析实际门实体。
     */
    public static @Nullable DoorBlockEntity findDoor(World world, BlockPos clickedPos) {
        BlockEntity blockEntity = world.getBlockEntity(clickedPos);
        if (blockEntity instanceof DoorBlockEntity door) {
            return door;
        }

        BlockState state = world.getBlockState(clickedPos);
        if (state.getBlock() instanceof SmallDoorBlock
                && state.get(SmallDoorBlock.HALF) == DoubleBlockHalf.UPPER
                && world.getBlockEntity(clickedPos.down()) instanceof DoorBlockEntity door) {
            return door;
        }
        return null;
    }

    /**
     * 返回目标门以及与它正确配对的另一侧。列表不可修改，单门只包含自身。
     */
    public static List<DoorBlockEntity> getGroupMembers(DoorBlockEntity origin) {
        World world = origin.getWorld();
        if (world == null) {
            return List.of(origin);
        }

        List<DoorBlockEntity> members = new ArrayList<>(2);
        members.add(origin);
        BlockState state = world.getBlockState(origin.getPos());
        if (state.getBlock() instanceof SmallDoorBlock) {
            DoorBlockEntity neighbor = SmallDoorBlock.getNeighborDoorEntity(state, world, origin.getPos());
            if (neighbor != null && neighbor != origin) {
                members.add(neighbor);
            }
        }
        return Collections.unmodifiableList(members);
    }

    public static GroupState inspect(DoorBlockEntity origin) {
        List<DoorBlockEntity> members = getGroupMembers(origin);
        boolean blasted = false;
        boolean jammed = false;
        for (DoorBlockEntity member : members) {
            blasted |= member.isBlastedDirect();
            jammed |= member.isJammedDirect();
        }
        return new GroupState(members.size(), blasted, jammed);
    }

    public static boolean isBlasted(DoorBlockEntity origin) {
        return inspect(origin).blasted();
    }

    public static boolean isJammed(DoorBlockEntity origin) {
        return inspect(origin).jammed();
    }

    /**
     * 修复旧版本可能遗留的“关闭但 blasted”或只有一侧 blasted 的非法状态。
     * 服务端门 tick 会调用该方法；正常门不会产生任何写入和网络同步。
     */
    public static void maintainGroupInvariants(DoorBlockEntity origin) {
        World world = origin.getWorld();
        if (world == null || world.isClient) {
            return;
        }
        GroupState state = inspect(origin);
        if (!state.blasted()) {
            return;
        }
        boolean invalid = false;
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            if (!member.isBlastedDirect() || !member.isOpen() || member.getCloseCountdown() != 0) {
                invalid = true;
                break;
            }
        }
        if (invalid) {
            normalizeBlastedGroup(origin);
        }
    }

    /**
     * 普通玩家、按钮和自动关门使用的切换入口。
     *
     * <p>不再以“同一世界 tick 只能更新一次”吞掉后续真实交互。门组没有递归调用，因此同 tick
     * 的多个操作可以严格按服务端收到的顺序执行。若发现旧版本遗留的左右状态不一致，本次切换
     * 会以被操作一侧为基准，把整组写成同一个明确目标状态。</p>
     */
    public static boolean toggle(DoorBlockEntity origin, boolean silent) {
        if (origin.getWorld() == null) {
            return false;
        }
        if (isBlasted(origin)) {
            // 旧存档可能只有一侧带 blasted。检测到后立即恢复“整组永久打开”的不变量。
            normalizeBlastedGroup(origin);
            return false;
        }
        return setOpen(origin, !origin.isOpen(), silent, true);
    }

    /**
     * 把整个门组写成指定开关状态。该方法只改变开关与自动关门倒计时，不改变损坏/锁定标记。
     */
    public static boolean setOpen(DoorBlockEntity origin, boolean open, boolean silent, boolean startAutoclose) {
        World world = origin.getWorld();
        if (world == null || world.isClient) {
            return false;
        }

        int closeCountdown = open && startAutoclose ? GameConstants.DOOR_AUTOCLOSE_TIME : 0;
        boolean changed = false;
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            changed |= member.applyOpenState(open, closeCountdown);
        }
        if (changed && !silent) {
            origin.playToggleSound();
        }
        return changed;
    }

    /** 永久撬开整个门组。普通关门和自动关门之后都不能再关闭它。 */
    public static OperationResult pryOpen(DoorBlockEntity origin) {
        World world = origin.getWorld();
        GroupState before = inspect(origin);
        if (world == null || world.isClient) {
            return result(false, false, before);
        }

        boolean changed = setOpen(origin, true, false, false);
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            changed |= !member.isBlastedDirect() || member.getCloseCountdown() != 0;
            member.setBlastedDirect(true);
            member.setCloseCountdown(0);
            member.sync();
        }
        return result(true, changed, before);
    }

    /**
     * 临时锁住并强制关闭整个门组。永久撬开的门优先级更高，不能被开锁器重新锁成关闭状态。
     */
    public static OperationResult jamClosed(DoorBlockEntity origin, int jamTicks) {
        World world = origin.getWorld();
        GroupState before = inspect(origin);
        if (world == null || world.isClient) {
            return result(false, false, before);
        }
        if (before.blasted()) {
            normalizeBlastedGroup(origin);
            return result(false, false, before);
        }

        boolean changed = setOpen(origin, false, false, false);
        int safeTicks = Math.max(0, jamTicks);
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            changed |= member.getJammedTime() != safeTicks;
            member.setJammedDirect(safeTicks);
            member.setCloseCountdown(0);
            member.sync();
        }
        // 刷新已经锁住的门也属于一次有效使用，即使恰好在同一 tick 写入了相同数值。
        return result(true, changed, before);
    }

    /**
     * 清除整个门组的损坏与锁定状态，并按调用方选择的策略统一两侧开关状态。
     */
    public static OperationResult repair(DoorBlockEntity origin, RepairMode mode) {
        return repair(origin, mode, false);
    }

    /**
     * 带门开关声控制的修复入口。silent 只控制 Wathe 普通门声，调用方自己的修理音不受影响。
     */
    public static OperationResult repair(DoorBlockEntity origin, RepairMode mode, boolean silent) {
        World world = origin.getWorld();
        GroupState before = inspect(origin);
        if (world == null || world.isClient || !before.needsRepair()) {
            return result(false, false, before);
        }

        for (DoorBlockEntity member : getGroupMembers(origin)) {
            member.setBlastedDirect(false);
            member.setJammedDirect(0);
        }
        boolean open = mode == RepairMode.OPEN_THEN_AUTOCLOSE;
        boolean changed = setOpen(origin, open, silent, open);
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            member.sync();
        }
        return result(true, changed || before.needsRepair(), before);
    }

    private static void normalizeBlastedGroup(DoorBlockEntity origin) {
        if (origin.getWorld() == null || origin.getWorld().isClient) {
            return;
        }
        setOpen(origin, true, true, false);
        for (DoorBlockEntity member : getGroupMembers(origin)) {
            member.setBlastedDirect(true);
            member.setCloseCountdown(0);
            member.sync();
        }
    }

    private static OperationResult result(boolean applied, boolean changed, GroupState before) {
        return new OperationResult(applied, changed, before.doorCount(), before.blasted(), before.jammed());
    }
}
