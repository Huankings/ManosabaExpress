package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Wathe 心情任务发放系统的公开接入点。
 *
 * <p>原本多任务只会由 {@link PlayerMoodComponent} 按真实心情阈值自动补刷：
 * 心情低到第二/第三任务阈值后，才会把任务数补到 2/3 个。
 * 这个 API 给扩展职业提供一个更直接的入口，让技能、事件或特殊规则可以主动给玩家发放随机心情任务，
 * 但仍然复用 Wathe 原本的任务抽取、去重、权重、卡死计数和同步逻辑。</p>
 *
 * <p>重要边界：</p>
 * <p>1. 只接受 {@link ServerPlayerEntity}，客户端不能本地伪造任务；</p>
 * <p>2. 只会给对局中仍按 Wathe 玩法存活的玩家发任务；</p>
 * <p>3. 只支持 REAL/FAKE 心情职业，MoodType.NONE 不会被强行挂任务；</p>
 * <p>4. 最多同时存在 {@link GameConstants#MAX_CONCURRENT_MOOD_TASKS} 个任务，达到上限后返回失败结果。</p>
 */
public final class MoodTaskApi {
    private MoodTaskApi() {
    }

    /**
     * 主动发放 1 个随机心情任务。
     */
    public static @NotNull TaskAssignmentResult assignRandomTask(@NotNull ServerPlayerEntity player) {
        return assignRandomTasks(player, 1);
    }

    /**
     * 主动发放指定数量的随机心情任务。
     *
     * <p>如果 requestedCount 大于剩余槽位，这里只会补到最大同时任务上限，并通过
     * {@link AssignmentStatus#PARTIAL_SUCCESS} 告诉调用方“成功发了一部分”。</p>
     */
    public static @NotNull TaskAssignmentResult assignRandomTasks(
            @NotNull ServerPlayerEntity player,
            int requestedCount
    ) {
        Objects.requireNonNull(player, "player");

        PlayerMoodComponent moodComponent = PlayerMoodComponent.KEY.get(player);
        int activeTaskCount = moodComponent.getActiveMoodTaskCount();
        int maxTaskCount = GameConstants.MAX_CONCURRENT_MOOD_TASKS;

        if (requestedCount <= 0) {
            return TaskAssignmentResult.empty(AssignmentStatus.INVALID_COUNT, requestedCount, activeTaskCount, maxTaskCount);
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        if (!gameWorld.isRunning()) {
            return TaskAssignmentResult.empty(AssignmentStatus.GAME_NOT_RUNNING, requestedCount, activeTaskCount, maxTaskCount);
        }

        if (!GameFunctions.isPlayerAliveAndSurvival(player)) {
            return TaskAssignmentResult.empty(AssignmentStatus.PLAYER_NOT_ALIVE, requestedCount, activeTaskCount, maxTaskCount);
        }

        Role role = gameWorld.getRole(player);
        if (role == null || role.getMoodType() == Role.MoodType.NONE) {
            return TaskAssignmentResult.empty(AssignmentStatus.MOOD_TASKS_UNSUPPORTED, requestedCount, activeTaskCount, maxTaskCount);
        }

        if (activeTaskCount >= maxTaskCount) {
            return TaskAssignmentResult.empty(AssignmentStatus.TASK_LIMIT_REACHED, requestedCount, activeTaskCount, maxTaskCount);
        }

        List<PlayerMoodComponent.Task> assignedTasks = moodComponent.assignExternalRandomTasks(requestedCount);
        if (assignedTasks.isEmpty()) {
            return TaskAssignmentResult.empty(
                    AssignmentStatus.NO_AVAILABLE_TASK,
                    requestedCount,
                    moodComponent.getActiveMoodTaskCount(),
                    maxTaskCount
            );
        }

        AssignmentStatus status = assignedTasks.size() < requestedCount
                ? AssignmentStatus.PARTIAL_SUCCESS
                : AssignmentStatus.SUCCESS;
        return new TaskAssignmentResult(
                status,
                requestedCount,
                assignedTasks,
                moodComponent.getActiveMoodTaskCount(),
                maxTaskCount
        );
    }

    /**
     * 直接把玩家当前任务补满到 Wathe 允许的最大同时任务数。
     *
     * <p>这个方法适合“技能触发后让目标立刻进入多任务压力”的职业。已有任务会保留，
     * API 只负责补空位，不会替换或删除当前任务。</p>
     */
    public static @NotNull TaskAssignmentResult fillRandomTaskSlots(@NotNull ServerPlayerEntity player) {
        Objects.requireNonNull(player, "player");

        int remainingSlots = PlayerMoodComponent.KEY.get(player).getRemainingMoodTaskSlots();
        if (remainingSlots <= 0) {
            return assignRandomTasks(player, 1);
        }
        return assignRandomTasks(player, remainingSlots);
    }

    /**
     * 轻量检查当前玩家是否还能再接收至少 1 个外部心情任务。
     */
    public static boolean canAssignRandomTask(@NotNull ServerPlayerEntity player) {
        Objects.requireNonNull(player, "player");
        return PlayerMoodComponent.KEY.get(player).canReceiveExternalMoodTask();
    }

    public record TaskAssignmentResult(
            @NotNull AssignmentStatus status,
            int requestedCount,
            @NotNull List<PlayerMoodComponent.Task> assignedTasks,
            int activeTaskCount,
            int maxTaskCount
    ) {
        public TaskAssignmentResult {
            Objects.requireNonNull(status, "status");
            assignedTasks = List.copyOf(Objects.requireNonNull(assignedTasks, "assignedTasks"));
        }

        private static @NotNull TaskAssignmentResult empty(
                @NotNull AssignmentStatus status,
                int requestedCount,
                int activeTaskCount,
                int maxTaskCount
        ) {
            return new TaskAssignmentResult(status, requestedCount, List.of(), activeTaskCount, maxTaskCount);
        }

        /**
         * 是否至少成功发出了 1 个任务。
         */
        public boolean success() {
            return !this.assignedTasks.isEmpty();
        }

        public int assignedCount() {
            return this.assignedTasks.size();
        }
    }

    public enum AssignmentStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        INVALID_COUNT,
        GAME_NOT_RUNNING,
        PLAYER_NOT_ALIVE,
        MOOD_TASKS_UNSUPPORTED,
        TASK_LIMIT_REACHED,
        NO_AVAILABLE_TASK
    }
}
