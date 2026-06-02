package dev.doctor4t.wathe.client.task;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.task.TaskPointType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端任务点透视状态。
 *
 * <p>这里保存两类本地数据：
 * 1. 玩家自己是否开启了任务点透视；
 * 2. 服务端最近一次同步下来的任务点缓存。
 */
public final class TaskPointClientState {
    private static boolean taskPointOverlayEnabled = false;
    private static final HashMap<BlockPos, EnumSet<TaskPointType>> TASK_POINTS = new HashMap<>();

    private TaskPointClientState() {
    }

    public static boolean isTaskPointOverlayEnabled() {
        return taskPointOverlayEnabled;
    }

    public static void setTaskPointOverlayEnabled(boolean enabled) {
        taskPointOverlayEnabled = enabled;
    }

    public static boolean toggleTaskPointOverlayEnabled() {
        taskPointOverlayEnabled = !taskPointOverlayEnabled;
        return taskPointOverlayEnabled;
    }

    /**
     * 用服务端新发来的整张任务点表直接替换本地缓存。
     */
    public static void replaceTaskPoints(@NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints) {
        TASK_POINTS.clear();
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : taskPoints.entrySet()) {
            TASK_POINTS.put(entry.getKey().toImmutable(), EnumSet.copyOf(entry.getValue()));
        }
    }

    /**
     * 清空本地缓存。
     * 通常在断开连接时调用，避免下一次进服前保留旧地图数据。
     */
    public static void clear() {
        TASK_POINTS.clear();
    }

    public static @NotNull HashMap<BlockPos, EnumSet<TaskPointType>> createSnapshot() {
        HashMap<BlockPos, EnumSet<TaskPointType>> copy = new HashMap<>();
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : TASK_POINTS.entrySet()) {
            copy.put(entry.getKey().toImmutable(), EnumSet.copyOf(entry.getValue()));
        }
        return copy;
    }

    /**
     * 根据玩家当前任务，计算此刻允许显示哪些任务点类型。
     *
     * <p>如果玩家是局内存活玩家，就只返回“当前任务真正需要的点”；
     * 如果是旁观/创造一类非局内存活玩家，则由外层渲染逻辑直接显示全部类型。
     */
    public static @NotNull EnumSet<TaskPointType> collectVisibleTypesForAlivePlayer(@NotNull PlayerEntity player) {
        EnumSet<TaskPointType> visibleTypes = EnumSet.noneOf(TaskPointType.class);
        PlayerMoodComponent moodComponent = PlayerMoodComponent.KEY.get(player);
        for (PlayerMoodComponent.Task task : moodComponent.tasks.keySet()) {
            visibleTypes.addAll(TaskPointType.getTypesForTask(task));
        }
        return visibleTypes;
    }
}
