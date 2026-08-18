package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wathe 心情任务系统的公开接入点。
 *
 * <p>旧版 API 只允许扩展职业“主动发放随机任务”，任务本身仍被 {@link PlayerMoodComponent.Task} 枚举锁死。
 * 现在任务改为按 {@link Identifier} 注册：Wathe 内置任务会在这里注册为默认任务，
 * 扩展 mod 可以注册自己的任务定义、指定发放、移除或调试完成。</p>
 *
 * <p>默认策略：</p>
 * <p>1. Wathe 内置任务显式加入随机池；</p>
 * <p>2. 扩展任务默认不进随机池，只能通过 {@link #assignTask(ServerPlayerEntity, Identifier)} 指定发放；</p>
 * <p>3. 所有外部发放仍然遵守 {@link GameConstants#MAX_CONCURRENT_MOOD_TASKS} 上限和 Wathe 的对局/存活检查。</p>
 */
public final class MoodTaskApi {
    public static final int DEFAULT_PRIORITY = 0;

    public static final Identifier SLEEP = Wathe.id("sleep");
    public static final Identifier OUTSIDE = Wathe.id("outside");
    public static final Identifier WATER = Wathe.id("water");
    public static final Identifier SHIFT = Wathe.id("shift");
    public static final Identifier STARE = Wathe.id("stare");
    public static final Identifier AWAY = Wathe.id("away");
    public static final Identifier EAT = Wathe.id("eat");
    public static final Identifier DRINK = Wathe.id("drink");
    public static final Identifier RUN = Wathe.id("run");
    public static final Identifier SIT = Wathe.id("sit");
    public static final Identifier POTION = Wathe.id("potion");
    public static final Identifier MUSIC = Wathe.id("music");
    public static final Identifier BOOK = Wathe.id("book");
    public static final Identifier STAY = Wathe.id("stay");
    public static final Identifier FISH = Wathe.id("fish");
    public static final Identifier FIRE = Wathe.id("fire");
    public static final Identifier COOK = Wathe.id("cook");

    private static final Map<Identifier, MoodTaskDefinition> TASK_DEFINITIONS = new LinkedHashMap<>();
    private static final Map<PlayerMoodComponent.Task, Identifier> LEGACY_TASK_IDS = new LinkedHashMap<>();
    private static final ArrayList<PrioritizedAssignmentRule> ASSIGNMENT_RULES = new ArrayList<>();
    private static final ArrayList<PrioritizedCompletionRule> COMPLETION_RULES = new ArrayList<>();
    private static long nextAssignmentRuleOrder = 0L;
    private static long nextCompletionRuleOrder = 0L;

    static {
        registerBuiltInTasks();
    }

    private MoodTaskApi() {
    }

    /**
     * 注册或替换一个心情任务定义。
     *
     * <p>扩展 mod 通常应在自己的 main entrypoint 中调用。
     * 同 id 重复注册时后者覆盖前者，便于开发期调整定义；但不建议多个 mod 抢同一个 id。</p>
     */
    public static synchronized void registerTask(@NotNull MoodTaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        TASK_DEFINITIONS.put(definition.id(), definition);
        if (definition.legacyTask() != null) {
            LEGACY_TASK_IDS.put(definition.legacyTask(), definition.id());
        }
    }

    public static synchronized @Nullable MoodTaskDefinition getDefinition(@NotNull Identifier taskId) {
        return TASK_DEFINITIONS.get(taskId);
    }

    public static synchronized @NotNull List<MoodTaskDefinition> getDefinitions() {
        return List.copyOf(TASK_DEFINITIONS.values());
    }

    public static synchronized @NotNull List<Identifier> getRegisteredTaskIds() {
        return List.copyOf(TASK_DEFINITIONS.keySet());
    }

    public static synchronized @NotNull List<MoodTaskDefinition> getRandomAssignableDefinitions() {
        return TASK_DEFINITIONS.values().stream()
                .filter(MoodTaskDefinition::randomlyAssignable)
                .toList();
    }

    public static boolean isRegistered(@NotNull Identifier taskId) {
        return getDefinition(taskId) != null;
    }

    public static @NotNull Identifier getTaskId(@NotNull PlayerMoodComponent.Task legacyTask) {
        Identifier taskId = LEGACY_TASK_IDS.get(Objects.requireNonNull(legacyTask, "legacyTask"));
        return taskId == null ? Wathe.id(legacyTask.name().toLowerCase()) : taskId;
    }

    public static @Nullable PlayerMoodComponent.Task getLegacyTask(@NotNull Identifier taskId) {
        MoodTaskDefinition definition = getDefinition(taskId);
        return definition == null ? null : definition.legacyTask();
    }

    public static @NotNull String getTranslationKey(@NotNull Identifier taskId) {
        MoodTaskDefinition definition = getDefinition(taskId);
        return definition == null ? "replay.task.unknown" : definition.translationKey();
    }

    public static @NotNull List<Identifier> getTaskPointIds(@NotNull Identifier taskId) {
        MoodTaskDefinition definition = getDefinition(taskId);
        return definition == null ? List.of() : List.copyOf(definition.taskPointIds());
    }

    /**
     * 注册一个“任务即将发放”拦截规则。
     *
     * <p>这条链会覆盖 Wathe 自己的冷却刷任务、低心情补槽、外部随机发放和外部指定发放。
     * priority 越大越先执行，第一个返回 {@link AssignmentDecision#DENY} 的规则会阻止本次发放。</p>
     *
     * <p>随机发放会在候选任务被放进任务栏之前逐个询问规则；如果某个候选被拒绝，
     * Wathe 会继续尝试其它随机候选，避免一个扩展职业屏蔽某类任务后把整套随机池卡死。</p>
     */
    public static synchronized void registerAssignmentRule(
            @NotNull Identifier id,
            int priority,
            @NotNull AssignmentRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        ASSIGNMENT_RULES.removeIf(entry -> entry.id().equals(id));
        ASSIGNMENT_RULES.add(new PrioritizedAssignmentRule(id, priority, nextAssignmentRuleOrder++, rule));
        ASSIGNMENT_RULES.sort(
                Comparator.<PrioritizedAssignmentRule>comparingInt(PrioritizedAssignmentRule::priority)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(PrioritizedAssignmentRule::order).reversed())
        );
    }

    public static boolean canAssignTask(@NotNull MoodTaskAssignmentContext context) {
        for (PrioritizedAssignmentRule entry : assignmentRuleSnapshot()) {
            if (entry.rule().canAssign(context) == AssignmentDecision.DENY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 注册一个“任务即将完成”拦截规则。
     *
     * <p>这里服务于灵术师附身这类需求：任务进度可以正常存在，但某些特殊状态下不允许真的完成。
     * priority 越大越先执行，第一个返回 {@link CompletionDecision#DENY} 的规则会阻止本次完成。</p>
     */
    public static synchronized void registerCompletionRule(
            @NotNull Identifier id,
            int priority,
            @NotNull CompletionRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        COMPLETION_RULES.removeIf(entry -> entry.id().equals(id));
        COMPLETION_RULES.add(new PrioritizedCompletionRule(id, priority, nextCompletionRuleOrder++, rule));
        COMPLETION_RULES.sort(
                Comparator.<PrioritizedCompletionRule>comparingInt(PrioritizedCompletionRule::priority)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(PrioritizedCompletionRule::order).reversed())
        );
    }

    public static boolean canCompleteTask(@NotNull MoodTaskCompletionContext context) {
        for (PrioritizedCompletionRule entry : completionRuleSnapshot()) {
            if (entry.rule().canComplete(context) == CompletionDecision.DENY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 主动发放 1 个随机心情任务。
     */
    public static @NotNull TaskAssignmentResult assignRandomTask(@NotNull ServerPlayerEntity player) {
        return assignRandomTasks(player, 1);
    }

    /**
     * 主动发放指定数量的随机心情任务。
     */
    public static @NotNull TaskAssignmentResult assignRandomTasks(
            @NotNull ServerPlayerEntity player,
            int requestedCount
    ) {
        Objects.requireNonNull(player, "player");

        PlayerMoodComponent moodComponent = PlayerMoodComponent.KEY.get(player);
        int activeTaskCount = moodComponent.getActiveMoodTaskCount();
        int maxTaskCount = GameConstants.MAX_CONCURRENT_MOOD_TASKS;

        AssignmentStatus validationStatus = validateAssignmentBase(player, requestedCount);
        if (validationStatus != AssignmentStatus.SUCCESS) {
            return TaskAssignmentResult.empty(validationStatus, requestedCount, activeTaskCount, maxTaskCount);
        }

        List<Identifier> assignedTasks = moodComponent.assignExternalRandomTasks(requestedCount);
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
     * 指定发放某一个任务。
     *
     * <p>这个入口不会检查任务是否在随机池里，因此扩展专属任务可以保持“不随机出现”，
     * 只在职业技能、事件或调试指令明确指定时发给玩家。</p>
     */
    public static @NotNull TaskAssignmentResult assignTask(
            @NotNull ServerPlayerEntity player,
            @NotNull Identifier taskId
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(taskId, "taskId");

        PlayerMoodComponent moodComponent = PlayerMoodComponent.KEY.get(player);
        int activeTaskCount = moodComponent.getActiveMoodTaskCount();
        int maxTaskCount = GameConstants.MAX_CONCURRENT_MOOD_TASKS;

        AssignmentStatus validationStatus = validateAssignmentBase(player, 1);
        if (validationStatus != AssignmentStatus.SUCCESS) {
            return TaskAssignmentResult.empty(validationStatus, 1, activeTaskCount, maxTaskCount);
        }

        if (!isRegistered(taskId)) {
            return TaskAssignmentResult.empty(AssignmentStatus.TASK_NOT_REGISTERED, 1, activeTaskCount, maxTaskCount);
        }

        if (moodComponent.hasMoodTask(taskId)) {
            return TaskAssignmentResult.empty(AssignmentStatus.TASK_ALREADY_ACTIVE, 1, activeTaskCount, maxTaskCount);
        }

        if (!moodComponent.canAssignExternalTask(taskId)) {
            return TaskAssignmentResult.empty(AssignmentStatus.ASSIGNMENT_DENIED, 1, activeTaskCount, maxTaskCount);
        }

        return moodComponent.assignExternalTask(taskId)
                ? new TaskAssignmentResult(AssignmentStatus.SUCCESS, 1, List.of(taskId), moodComponent.getActiveMoodTaskCount(), maxTaskCount)
                : TaskAssignmentResult.empty(AssignmentStatus.NO_AVAILABLE_TASK, 1, moodComponent.getActiveMoodTaskCount(), maxTaskCount);
    }

    /**
     * 直接把玩家当前任务补满到 Wathe 允许的最大同时任务数。
     */
    public static @NotNull TaskAssignmentResult fillRandomTaskSlots(@NotNull ServerPlayerEntity player) {
        Objects.requireNonNull(player, "player");

        int remainingSlots = PlayerMoodComponent.KEY.get(player).getRemainingMoodTaskSlots();
        if (remainingSlots <= 0) {
            return TaskAssignmentResult.empty(
                    AssignmentStatus.TASK_LIMIT_REACHED,
                    1,
                    PlayerMoodComponent.KEY.get(player).getActiveMoodTaskCount(),
                    GameConstants.MAX_CONCURRENT_MOOD_TASKS
            );
        }
        return assignRandomTasks(player, remainingSlots);
    }

    public static boolean canAssignRandomTask(@NotNull ServerPlayerEntity player) {
        Objects.requireNonNull(player, "player");
        return PlayerMoodComponent.KEY.get(player).canReceiveExternalMoodTask();
    }

    public static boolean hasTask(@NotNull ServerPlayerEntity player, @NotNull Identifier taskId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(taskId, "taskId");
        return PlayerMoodComponent.KEY.get(player).hasMoodTask(taskId);
    }

    public static boolean hasTask(@NotNull ServerPlayerEntity player, @NotNull PlayerMoodComponent.Task legacyTask) {
        return hasTask(player, getTaskId(legacyTask));
    }

    /**
     * 单纯移除玩家身上的任务。
     *
     * <p>这个方法不会加心情、不会触发任务完成事件，也不会记录回放。
     * 它适合管理员调试或扩展效果“清掉某个任务”的语义。</p>
     */
    public static @NotNull TaskOperationResult removeTask(
            @NotNull ServerPlayerEntity player,
            @NotNull Identifier taskId
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(taskId, "taskId");

        if (!isRegistered(taskId)) {
            return TaskOperationResult.of(TaskOperationStatus.TASK_NOT_REGISTERED, taskId, player);
        }
        if (!PlayerMoodComponent.KEY.get(player).removeExternalTask(taskId)) {
            return TaskOperationResult.of(TaskOperationStatus.TASK_NOT_ACTIVE, taskId, player);
        }
        return TaskOperationResult.of(TaskOperationStatus.SUCCESS, taskId, player);
    }

    /**
     * 按“任务正常完成”的语义完成某个任务。
     *
     * <p>和 {@link #removeTask(ServerPlayerEntity, Identifier)} 不同，这里会复用 Wathe 的完成流程：
     * 移除任务、按参数决定是否回复心情、发送完成提示、写回放、触发任务完成 API 和收入规则。</p>
     */
    public static @NotNull TaskOperationResult completeTask(
            @NotNull ServerPlayerEntity player,
            @NotNull Identifier taskId,
            boolean rewardMood
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(taskId, "taskId");

        if (!isRegistered(taskId)) {
            return TaskOperationResult.of(TaskOperationStatus.TASK_NOT_REGISTERED, taskId, player);
        }
        if (!PlayerMoodComponent.KEY.get(player).completeExternalTask(taskId, rewardMood)) {
            return TaskOperationResult.of(TaskOperationStatus.TASK_NOT_ACTIVE_OR_BLOCKED, taskId, player);
        }
        return TaskOperationResult.of(TaskOperationStatus.SUCCESS, taskId, player);
    }

    public static @NotNull TaskOperationResult completeTask(
            @NotNull ServerPlayerEntity player,
            @NotNull PlayerMoodComponent.Task legacyTask,
            boolean rewardMood
    ) {
        return completeTask(player, getTaskId(legacyTask), rewardMood);
    }

    private static @NotNull AssignmentStatus validateAssignmentBase(@NotNull ServerPlayerEntity player, int requestedCount) {
        PlayerMoodComponent moodComponent = PlayerMoodComponent.KEY.get(player);
        if (requestedCount <= 0) {
            return AssignmentStatus.INVALID_COUNT;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        if (!gameWorld.isRunning()) {
            return AssignmentStatus.GAME_NOT_RUNNING;
        }

        if (!GameFunctions.isPlayerAliveAndSurvival(player)) {
            return AssignmentStatus.PLAYER_NOT_ALIVE;
        }

        Role role = gameWorld.getRole(player);
        if (role == null || role.getMoodType() == Role.MoodType.NONE) {
            return AssignmentStatus.MOOD_TASKS_UNSUPPORTED;
        }

        if (moodComponent.getActiveMoodTaskCount() >= GameConstants.MAX_CONCURRENT_MOOD_TASKS) {
            return AssignmentStatus.TASK_LIMIT_REACHED;
        }

        return AssignmentStatus.SUCCESS;
    }

    private static synchronized @NotNull List<PrioritizedCompletionRule> completionRuleSnapshot() {
        return List.copyOf(COMPLETION_RULES);
    }

    private static synchronized @NotNull List<PrioritizedAssignmentRule> assignmentRuleSnapshot() {
        return List.copyOf(ASSIGNMENT_RULES);
    }

    private static void registerBuiltInTasks() {
        registerBuiltInTask(SLEEP, "task.sleep", PlayerMoodComponent.Task.SLEEP,
                player -> new PlayerMoodComponent.SleepTask(GameConstants.SLEEP_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.SleepTask(nbt.getInt("timer")),
                MoodTaskPointApi.BED);
        registerBuiltInTask(OUTSIDE, "task.outside", PlayerMoodComponent.Task.OUTSIDE,
                player -> new PlayerMoodComponent.OutsideTask(GameConstants.OUTSIDE_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.OutsideTask(nbt.getInt("timer")));
        registerBuiltInTask(WATER, "task.water", PlayerMoodComponent.Task.WATER,
                player -> new PlayerMoodComponent.WaterTask(GameConstants.WATER_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.WaterTask(nbt.getInt("timer")),
                MoodTaskPointApi.WATER_SOURCE);
        registerBuiltInTask(SHIFT, "task.shift", PlayerMoodComponent.Task.SHIFT,
                player -> new PlayerMoodComponent.ShiftTask(GameConstants.SHIFT_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.ShiftTask(nbt.getInt("timer")));
        registerBuiltInTask(STARE, "task.stare", PlayerMoodComponent.Task.STARE,
                player -> new PlayerMoodComponent.StareTask(GameConstants.STARE_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.StareTask(nbt.getInt("timer")));
        registerBuiltInTask(AWAY, "task.away", PlayerMoodComponent.Task.AWAY,
                player -> new PlayerMoodComponent.AwayTask(GameConstants.AWAY_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.AwayTask(nbt.getInt("timer")));
        registerBuiltInTask(EAT, "task.eat", PlayerMoodComponent.Task.EAT,
                player -> new PlayerMoodComponent.EatTask(),
                (player, nbt) -> new PlayerMoodComponent.EatTask(nbt.getBoolean("fulfilled")),
                MoodTaskPointApi.FOOD_TRAY);
        registerBuiltInTask(DRINK, "task.drink", PlayerMoodComponent.Task.DRINK,
                player -> new PlayerMoodComponent.DrinkTask(),
                (player, nbt) -> new PlayerMoodComponent.DrinkTask(nbt.getBoolean("fulfilled")),
                MoodTaskPointApi.COCKTAIL_TRAY);
        registerBuiltInTask(RUN, "task.run", PlayerMoodComponent.Task.RUN,
                player -> new PlayerMoodComponent.RunTask(GameConstants.RUN_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.RunTask(nbt.getInt("timer")));
        registerBuiltInTask(SIT, "task.sit", PlayerMoodComponent.Task.SIT,
                player -> new PlayerMoodComponent.SitTask(GameConstants.SIT_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.SitTask(nbt.getInt("timer")),
                MoodTaskPointApi.SEAT);
        registerBuiltInTask(POTION, "task.potion", PlayerMoodComponent.Task.POTION,
                player -> new PlayerMoodComponent.PotionTask(),
                (player, nbt) -> new PlayerMoodComponent.PotionTask(nbt.getBoolean("fulfilled")),
                MoodTaskPointApi.POTION_TRAY);
        registerBuiltInTask(MUSIC, "task.music", PlayerMoodComponent.Task.MUSIC,
                player -> new PlayerMoodComponent.MusicTask(),
                (player, nbt) -> new PlayerMoodComponent.MusicTask(nbt.getInt("count")),
                MoodTaskPointApi.NOTE_BLOCK);
        registerBuiltInTask(BOOK, "task.book", PlayerMoodComponent.Task.BOOK,
                player -> new PlayerMoodComponent.BookTask(GameConstants.BOOK_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.BookTask(nbt.getInt("timer")),
                MoodTaskPointApi.LECTERN);
        registerBuiltInTask(STAY, "task.stay", PlayerMoodComponent.Task.STAY,
                player -> new PlayerMoodComponent.StayTask(GameConstants.STAY_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.StayTask(nbt.getInt("timer")));
        registerBuiltInTask(FISH, "task.fish", PlayerMoodComponent.Task.FISH,
                player -> new PlayerMoodComponent.FishTask(),
                (player, nbt) -> new PlayerMoodComponent.FishTask(nbt.getBoolean("fulfilled")),
                MoodTaskPointApi.FISHING_ROD_TRAY);
        registerBuiltInTask(FIRE, "task.fire", PlayerMoodComponent.Task.FIRE,
                player -> new PlayerMoodComponent.FireTask(GameConstants.FIRE_TASK_DURATION),
                (player, nbt) -> new PlayerMoodComponent.FireTask(nbt.getInt("timer")),
                MoodTaskPointApi.FIRE_SOURCE);
        registerBuiltInTask(COOK, "task.cook", PlayerMoodComponent.Task.COOK,
                player -> new PlayerMoodComponent.CookTask(),
                (player, nbt) -> new PlayerMoodComponent.CookTask(nbt.getBoolean("fulfilled")),
                MoodTaskPointApi.FURNACE,
                MoodTaskPointApi.SMOKER,
                MoodTaskPointApi.RAW_FOOD_TRAY,
                MoodTaskPointApi.FUEL_TRAY);
    }

    private static void registerBuiltInTask(
            @NotNull Identifier id,
            @NotNull String translationKey,
            @NotNull PlayerMoodComponent.Task legacyTask,
            @NotNull MoodTaskDefinition.TaskFactory factory,
            @NotNull MoodTaskDefinition.TaskNbtReader nbtReader,
            @NotNull Identifier... taskPointIds
    ) {
        /*
         * 内置任务明确加入随机池；扩展任务如果不调用 randomlyAssignable()，默认只允许指定发放。
         */
        registerTask(MoodTaskDefinition.builder(id, translationKey, factory, nbtReader)
                .legacyTask(legacyTask)
                .randomlyAssignable()
                .taskPoints(taskPointIds)
                .build());
    }

    public record TaskAssignmentResult(
            @NotNull AssignmentStatus status,
            int requestedCount,
            @NotNull List<Identifier> assignedTasks,
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

        public boolean success() {
            return !this.assignedTasks.isEmpty();
        }

        public int assignedCount() {
            return this.assignedTasks.size();
        }
    }

    public record TaskOperationResult(
            @NotNull TaskOperationStatus status,
            @NotNull Identifier taskId,
            int activeTaskCount,
            int maxTaskCount
    ) {
        private static @NotNull TaskOperationResult of(
                @NotNull TaskOperationStatus status,
                @NotNull Identifier taskId,
                @NotNull ServerPlayerEntity player
        ) {
            PlayerMoodComponent mood = PlayerMoodComponent.KEY.get(player);
            return new TaskOperationResult(status, taskId, mood.getActiveMoodTaskCount(), GameConstants.MAX_CONCURRENT_MOOD_TASKS);
        }

        public boolean success() {
            return this.status == TaskOperationStatus.SUCCESS;
        }
    }

    public record MoodTaskCompletionContext(
            @NotNull ServerPlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @NotNull Identifier taskId,
            @Nullable MoodTaskDefinition taskDefinition,
            @Nullable PlayerMoodComponent.Task legacyTask,
            boolean rewardedMood
    ) {
    }

    public record MoodTaskAssignmentContext(
            @NotNull ServerPlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @Nullable Identifier taskId,
            @Nullable MoodTaskDefinition taskDefinition,
            @Nullable PlayerMoodComponent.Task legacyTask,
            @NotNull AssignmentSource source,
            boolean random,
            int activeTaskCount,
            int maxTaskCount
    ) {
    }

    @FunctionalInterface
    public interface AssignmentRule {
        @NotNull AssignmentDecision canAssign(@NotNull MoodTaskAssignmentContext context);
    }

    @FunctionalInterface
    public interface CompletionRule {
        @NotNull CompletionDecision canComplete(@NotNull MoodTaskCompletionContext context);
    }

    public enum AssignmentDecision {
        PASS,
        DENY
    }

    public enum CompletionDecision {
        PASS,
        DENY
    }

    public enum AssignmentSource {
        /**
         * Wathe 在任务栏为空并且第一个任务冷却结束后自动刷出的任务。
         */
        INTERNAL_PRIMARY_COOLDOWN,
        /**
         * Wathe 根据低心情并行槽位，或任务完成后的当前阈值，自动补上的额外任务。
         */
        INTERNAL_SLOT_REFILL,
        /**
         * 扩展或调试入口通过 {@link #assignRandomTask(ServerPlayerEntity)} /
         * {@link #assignRandomTasks(ServerPlayerEntity, int)} 主动请求的随机任务。
         */
        EXTERNAL_RANDOM,
        /**
         * 扩展或调试入口通过 {@link #assignTask(ServerPlayerEntity, Identifier)} 主动指定的任务。
         */
        EXTERNAL_SPECIFIC
    }

    public enum AssignmentStatus {
        SUCCESS,
        PARTIAL_SUCCESS,
        INVALID_COUNT,
        GAME_NOT_RUNNING,
        PLAYER_NOT_ALIVE,
        MOOD_TASKS_UNSUPPORTED,
        TASK_LIMIT_REACHED,
        TASK_NOT_REGISTERED,
        TASK_ALREADY_ACTIVE,
        ASSIGNMENT_DENIED,
        NO_AVAILABLE_TASK
    }

    public enum TaskOperationStatus {
        SUCCESS,
        TASK_NOT_REGISTERED,
        TASK_NOT_ACTIVE,
        TASK_NOT_ACTIVE_OR_BLOCKED
    }

    private record PrioritizedCompletionRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull CompletionRule rule
    ) {
    }

    private record PrioritizedAssignmentRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull AssignmentRule rule
    ) {
    }
}
