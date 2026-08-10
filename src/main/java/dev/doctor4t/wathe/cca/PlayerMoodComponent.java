package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.task.MoodTaskApi;
import dev.doctor4t.wathe.api.task.MoodTaskDefinition;
import dev.doctor4t.wathe.api.task.TaskCompletionApi;
import dev.doctor4t.wathe.block.entity.SeatEntity;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import dev.doctor4t.wathe.item.ItemWithSkin;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.TaskCompletePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static dev.doctor4t.wathe.Wathe.isSkyVisibleAdjacent;

/**
 * 玩家心情组件。
 *
 * <p>这个组件负责维护以下几类状态：
 * 1. 当前真实心情值；
 * 2. 当前激活的任务、任务抽取权重与任务累计进度；
 * 3. “按当前心情阈值决定应同时存在几个任务”的运行时状态；
 * 4. “卡死任务自动删除”的累计计数；
 * 5. 低心情时客户端看到的幻视物品缓存。
 *
 * <p>本次改造后的关键点如下：
 * 1. 心情仍然被限制在 0~1；
 * 2. 只要身上还有任意任务，就按原版单任务速度持续掉心情，不再按任务数量叠乘；
 * 3. 第一个任务依旧走时间冷却刷新；
 * 4. 第二、第三个任务不会永久解锁，而是只在当前心情真的落入对应阈值区间时补刷；
 * 5. 任务做完后，其他任务不会消失；
 * 6. 如果某个任务一直留在任务栏里，系统会累计“其他任务完成了多少次”，达到阈值后自动清掉该卡死任务，但不会给心情奖励；
 * 7. 扩展可以通过公开 API 主动发放任务，但最多仍只能同时存在 {@link GameConstants#MAX_CONCURRENT_MOOD_TASKS} 个任务。
 */
public class PlayerMoodComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<PlayerMoodComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("mood"), PlayerMoodComponent.class);

    private final PlayerEntity player;

    /**
     * 当前激活中的 Wathe 内置任务兼容视图。
     *
     * <p>真正的任务表已经迁移到 {@link #activeTasks}，key 是稳定的 {@link Identifier}。
     * 这里仍然保留旧 Map，是为了兼容 NoellesRoles / StarryExpress 等旧扩展里直接读取
     * {@code PlayerMoodComponent#tasks} 的内置任务逻辑。扩展新增任务不会出现在这个旧 Map 里，
     * 后续新代码应改用 {@link #getActiveMoodTaskIds()}、{@link #hasMoodTask(Identifier)} 等 id 入口。</p>
     */
    public final Map<Task, TrainTask> tasks = new HashMap<>();

    /**
     * 当前激活中的全部任务。
     *
     * <p>key 使用任务注册 id，value 是该任务在这个玩家身上的运行时状态。
     * 内置任务和扩展任务都在这里统一 tick、保存、完成和渲染。</p>
     */
    private final Map<Identifier, TrainTask> activeTasks = new LinkedHashMap<>();

    /**
     * 旧内置任务被抽到过多少次的兼容视图。
     *
     * <p>真正的重复计数保存在 {@link #timesGottenById}。这个旧 Map 只同步内置任务，
     * 避免旧扩展读取字段时看不到 Wathe 原有任务数据。</p>
     */
    public final Map<Task, Integer> timesGotten = new HashMap<>();

    /**
     * 记录某类任务被抽到过多少次。
     * 次数越高，下次再抽到时权重越低，用于避免过度重复。
     */
    private final Map<Identifier, Integer> timesGottenById = new HashMap<>();

    /**
     * 记录“某个任务挂在任务栏期间，其他任务已经完成了多少次”。
     * 这是卡死任务自动删除机制的核心累计值。
     */
    private final Map<Identifier, Integer> stuckTaskCompletionCounts = new HashMap<>();

    /**
     * 第一个任务槽位的刷新倒计时。
     * 只有当任务栏为空时才会倒计时，保持原版“做完再等一段时间刷一个”的节奏。
     */
    private int nextTaskTimer = GameConstants.TIME_TO_FIRST_TASK;

    /**
     * 当前真实心情值，范围固定为 0~1。
     */
    private float mood = 1f;

    /**
     * 额外心情下降倍率。
     *
     * <p>默认值为 1，表示沿用 Wathe 原版掉 san 速度。
     * 扩展职业模组如果想要让某个玩家“掉 san 更慢 / 更快”，
     * 只需要改这里，而不必再去 mixin 整段心情任务逻辑。</p>
     */
    private float externalMoodDrainMultiplier = 1f;

    /**
     * 额外“暂停心情下降”剩余时间。
     *
     * <p>只要这个值大于 0，玩家即便身上还有任务，也不会继续掉 san。
     * 这里专门预留给扩展职业的安抚、鼓舞、镇静等效果复用。</p>
     */
    private int externalMoodDrainProtectionTicks = 0;

    /**
     * 服务端改动聚合标记。
     * 我们在一个 tick 内把各种心情/任务变化先收集起来，最后只同步一次，避免过度刷包。
     */
    private boolean dirty = false;

    /**
     * 低心情时客户端看到的“幻视手持物品”。
     */
    private final HashMap<UUID, ItemStack> psychosisItems = new HashMap<>();

    private static List<Item> cachedPsychosisItems = null;

    public PlayerMoodComponent(PlayerEntity player) {
        this.player = player;
    }

    /**
     * 主动同步组件。
     * 只允许服务端真正发同步，客户端本地渲染时不会走这里。
     */
    public void sync() {
        if (!this.player.getWorld().isClient()) {
            KEY.sync(this.player);
        }
    }

    private void markDirty() {
        if (!this.player.getWorld().isClient()) {
            this.dirty = true;
        }
    }

    /**
     * 重置整套心情系统。
     * 会在开局、停局、玩家死亡等流程里调用。
     */
    public void reset() {
        this.activeTasks.clear();
        this.tasks.clear();
        this.timesGottenById.clear();
        this.timesGotten.clear();
        this.stuckTaskCompletionCounts.clear();
        this.nextTaskTimer = GameConstants.TIME_TO_FIRST_TASK;
        this.mood = 1f;
        this.externalMoodDrainMultiplier = 1f;
        this.externalMoodDrainProtectionTicks = 0;
        this.psychosisItems.clear();
        this.dirty = false;
        this.sync();
    }

    private @Nullable Role getCurrentRole() {
        return GameWorldComponent.KEY.get(this.player.getWorld()).getRole(this.player);
    }

    private boolean hasRealMood(@Nullable Role role) {
        return role != null && role.getMoodType() == Role.MoodType.REAL;
    }

    private boolean hasFakeMood(@Nullable Role role) {
        return role != null && role.getMoodType() == Role.MoodType.FAKE;
    }

    /**
     * 内部静默设置心情值。
     * 只更新状态并标脏，不会立刻同步，适合 serverTick 内部频繁调用。
     */
    private boolean setMoodSilently(float mood) {
        Role role = getCurrentRole();
        float oldMood = this.mood;

        if (hasRealMood(role)) {
            this.mood = MathHelper.clamp(mood, 0f, 1f);
        } else {
            this.mood = 1f;
        }

        if (oldMood != this.mood) {
            this.markDirty();
            return true;
        }
        return false;
    }

    /**
     * 对外暴露的设置心情方法。
     * 用于命令、调试或其他外部逻辑直接改值时，改完后会立即同步。
     */
    public void setMood(float mood) {
        boolean changed = this.setMoodSilently(mood);
        if (changed || this.dirty) {
            this.sync();
            this.dirty = false;
        }
    }

    /**
     * 设置额外心情下降倍率。
     *
     * <p>1 表示原速；0.5 表示减半；2 表示双倍。
     * 这里不会允许负数，避免外部错误配置把心情反向“越掉越高”。</p>
     */
    public void setMoodDrainMultiplier(float multiplier) {
        float sanitizedMultiplier = Math.max(0f, multiplier);
        if (this.externalMoodDrainMultiplier != sanitizedMultiplier) {
            this.externalMoodDrainMultiplier = sanitizedMultiplier;
            this.sync();
        }
    }

    public float getMoodDrainMultiplier() {
        return this.externalMoodDrainMultiplier;
    }

    /**
     * 设置额外“暂停心情下降”持续时间。
     *
     * <p>这里采用“直接覆盖为更大的值”的常见 buff 逻辑：
     * 如果新持续时间更长，就刷新；更短则忽略，避免短 buff 把长 buff 冲掉。</p>
     */
    public void setMoodDrainProtectionTicks(int ticks) {
        int sanitizedTicks = Math.max(0, ticks);
        if (sanitizedTicks > this.externalMoodDrainProtectionTicks) {
            this.externalMoodDrainProtectionTicks = sanitizedTicks;
            this.sync();
        }
    }

    public int getMoodDrainProtectionTicks() {
        return this.externalMoodDrainProtectionTicks;
    }

    public boolean hasMoodDrainProtection() {
        return this.externalMoodDrainProtectionTicks > 0;
    }

    /**
     * 主动清空所有扩展掉 san 控制状态。
     *
     * <p>给扩展职业的重置 / 转职清理逻辑使用。</p>
     */
    public void clearExternalMoodDrainState() {
        if (this.externalMoodDrainMultiplier != 1f || this.externalMoodDrainProtectionTicks != 0) {
            this.externalMoodDrainMultiplier = 1f;
            this.externalMoodDrainProtectionTicks = 0;
            this.sync();
        }
    }

    private float getCurrentMoodDrainPerTick() {
        return GameConstants.MOOD_DRAIN * this.externalMoodDrainMultiplier;
    }

    /**
     * 推进“临时免降 san”倒计时。
     *
     * <p>服务端每秒同步一次剩余时间，既能保证客户端 HUD/特效大致同步，
     * 又不会因为每 tick 都刷组件包而过度频繁。</p>
     */
    private void tickExternalMoodDrainProtection(boolean serverSide) {
        if (this.externalMoodDrainProtectionTicks <= 0) {
            return;
        }

        this.externalMoodDrainProtectionTicks--;
        if (serverSide && (this.externalMoodDrainProtectionTicks == 0 || this.externalMoodDrainProtectionTicks % 20 == 0)) {
            this.sync();
        }
    }

    /**
     * 根据“当前这一刻的真实心情值”判断，系统此时最多应该补刷到几个并行任务。
     *
     * <p>这里刻意不再沿用“本局永久解锁任务槽”的逻辑，而是改成严格参考当前心情阈值：
     * 1. 心情重新回升后，不会继续补刷新的替代任务；
     * 2. 但已经挂在任务栏上的旧任务会保留，直到玩家自己做完或被卡死清理机制删除；
     * 3. 只有当心情再次掉回对应阈值区间时，系统才会重新把任务数补回去。
     *
     * <p>这正好对应用户想要的体验：
     * 低心情时会同时出现多个任务，但做完其中一个以后如果心情涨回高区间，
     * 那么刚空出来的位置会暂时保持空缺，而不是立刻塞进一个替代任务。
     */
    private int getExpectedConcurrentTaskCount(@Nullable Role role) {
        if (!hasRealMood(role)) {
            return 1;
        }
        return Math.max(1, GameConstants.getUnlockedMoodTaskSlots(this.mood));
    }

    /**
     * 在任务栏为空时，为第一个任务槽位推进时间冷却。
     */
    private void tickPrimaryTaskCooldown(boolean allowExtraTaskSlots) {
        if (!this.activeTasks.isEmpty()) {
            return;
        }

        this.nextTaskTimer--;
        if (this.nextTaskTimer > 0) {
            return;
        }

        if (this.assignRandomTask() != null && allowExtraTaskSlots) {
            this.fillTasksUpToExpectedCount(this.getExpectedConcurrentTaskCount(getCurrentRole()));
        }

        this.nextTaskTimer = this.getRandomTaskCooldown();
    }

    /**
     * 只把任务数补到“当前心情阈值应有的数量”为止。
     *
     * <p>注意这里的判断是“只补不减”：
     * 1. 如果当前任务数低于目标任务数，就补刷；
     * 2. 如果当前任务数高于目标任务数，旧任务保留，不会被强行删掉；
     * 3. 因此心情回升后不会继续补替代任务，但原本没做完的任务仍会留在任务栏里。
     */
    private int fillTasksUpToExpectedCount(int expectedTaskCount) {
        if (this.activeTasks.isEmpty()) {
            return 0;
        }

        int cappedExpectedTaskCount = Math.min(expectedTaskCount, GameConstants.MAX_CONCURRENT_MOOD_TASKS);
        int filled = 0;
        while (this.activeTasks.size() < cappedExpectedTaskCount) {
            TrainTask task = this.assignRandomTask();
            if (task == null) {
                break;
            }
            filled++;
        }
        return filled;
    }

    /**
     * 抽取并挂上一个新任务。
     */
    private @Nullable TrainTask assignRandomTask() {
        MoodTaskDefinition definition = this.generateTaskDefinition();
        if (definition == null) {
            return null;
        }

        TrainTask task = definition.create(this.player);
        this.putActiveTask(definition.id(), task);
        this.timesGottenById.merge(definition.id(), 1, Integer::sum);
        syncLegacyTimesGotten(definition.id());
        return task;
    }

    /**
     * 原版第一个任务做完后，会在 30~60 秒之间重新刷一个任务。
     * 这里继续保留这个节奏。
     */
    private int getRandomTaskCooldown() {
        float random = this.player.getRandom().nextFloat();
        int cooldown = (int) (random * (GameConstants.MAX_TASK_COOLDOWN - GameConstants.MIN_TASK_COOLDOWN) + GameConstants.MIN_TASK_COOLDOWN);
        return Math.max(cooldown, 2);
    }

    /**
     * 当前已有多少个心情任务。
     *
     * <p>这个读入口给扩展 API 和调试逻辑使用，避免外部为了看数量直接依赖 {@link #tasks} 的可变 Map。</p>
     */
    public int getActiveMoodTaskCount() {
        return this.activeTasks.size();
    }

    /**
     * 当前还剩多少个可发放任务槽。
     *
     * <p>这里统一使用 {@link GameConstants#MAX_CONCURRENT_MOOD_TASKS}，确保低心情自动任务和扩展主动任务都共用同一条上限规则。</p>
     */
    public int getRemainingMoodTaskSlots() {
        return Math.max(0, GameConstants.MAX_CONCURRENT_MOOD_TASKS - this.activeTasks.size());
    }

    /**
     * 返回当前全部任务 id 的只读快照。
     *
     * <p>HUD、任务点透视和扩展调试逻辑都应使用这个入口读取任务，
     * 不要再直接依赖旧的 enum Map，否则扩展注册的新任务会被漏掉。</p>
     */
    public @NotNull List<Identifier> getActiveMoodTaskIds() {
        return List.copyOf(this.activeTasks.keySet());
    }

    public @Nullable TrainTask getMoodTask(@NotNull Identifier taskId) {
        return this.activeTasks.get(taskId);
    }

    public boolean hasMoodTask(@NotNull Identifier taskId) {
        return this.activeTasks.containsKey(taskId);
    }

    /**
     * 统一添加任务到新任务表，并同步旧 enum 兼容视图。
     */
    private void putActiveTask(@NotNull Identifier taskId, @NotNull TrainTask task) {
        this.putActiveTask(taskId, task, true);
    }

    private void putActiveTask(@NotNull Identifier taskId, @NotNull TrainTask task, boolean markDirty) {
        this.activeTasks.put(taskId, task);
        PlayerMoodComponent.Task legacyTask = MoodTaskApi.getLegacyTask(taskId);
        if (legacyTask != null) {
            this.tasks.put(legacyTask, task);
        }
        this.stuckTaskCompletionCounts.put(taskId, 0);
        if (markDirty) {
            this.markDirty();
        }
    }

    /**
     * 统一移除任务，并同步清理旧 enum 兼容视图。
     */
    private @Nullable TrainTask removeActiveTask(@NotNull Identifier taskId) {
        TrainTask removed = this.activeTasks.remove(taskId);
        if (removed != null) {
            PlayerMoodComponent.Task legacyTask = MoodTaskApi.getLegacyTask(taskId);
            if (legacyTask != null) {
                this.tasks.remove(legacyTask);
            }
            this.stuckTaskCompletionCounts.remove(taskId);
            this.markDirty();
        }
        return removed;
    }

    private void syncLegacyTimesGotten(@NotNull Identifier taskId) {
        PlayerMoodComponent.Task legacyTask = MoodTaskApi.getLegacyTask(taskId);
        if (legacyTask != null) {
            this.timesGotten.put(legacyTask, this.timesGottenById.getOrDefault(taskId, 0));
        }
    }

    /**
     * 判断当前玩家是否可以通过外部 API 获得新的心情任务。
     *
     * <p>扩展职业常见需求是“技能触发后直接给目标塞任务”，但这里仍然集中保护几个边界：</p>
     * <p>1. 只允许服务端改任务，客户端不能本地伪造任务；</p>
     * <p>2. 只在对局运行且玩家仍按 Wathe 玩法存活时发放，避免大厅/死亡状态留下脏任务；</p>
     * <p>3. 只有 REAL/FAKE 心情职业能接任务，MoodType.NONE 的展示型职业不会被强行挂任务；</p>
     * <p>4. 永远不能超过全局最大同时任务数。</p>
     */
    public boolean canReceiveExternalMoodTask() {
        if (this.player.getWorld().isClient()) {
            return false;
        }

        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
        Role role = gameWorldComponent.getRole(this.player);
        boolean supportsMoodTasks = hasRealMood(role) || hasFakeMood(role);
        return gameWorldComponent.isRunning()
                && GameFunctions.isPlayerAliveAndSurvival(this.player)
                && supportsMoodTasks
                && this.getRemainingMoodTaskSlots() > 0;
    }

    /**
     * 供公开 API 复用的“外部主动发任务”入口。
     *
     * <p>它复用 Wathe 原本的随机抽任务逻辑，因此仍然会：</p>
     * <p>1. 避免抽到当前已经存在的任务；</p>
     * <p>2. 维护 timesGotten 权重，减少同一任务反复出现；</p>
     * <p>3. 初始化卡死任务累计计数；</p>
     * <p>4. 统一同步给客户端。</p>
     *
     * <p>注意：这里“只是发任务”，不会触发任务完成事件，也不会额外改心情值。
     * 扩展如果需要奖励/惩罚，应在自己的技能逻辑里单独处理。</p>
     */
    public @NotNull List<Identifier> assignExternalRandomTasks(int requestedCount) {
        if (requestedCount <= 0 || !this.canReceiveExternalMoodTask()) {
            return List.of();
        }

        int tasksToAssign = Math.min(requestedCount, this.getRemainingMoodTaskSlots());
        ArrayList<Identifier> assignedTasks = new ArrayList<>();
        while (assignedTasks.size() < tasksToAssign) {
            MoodTaskDefinition definition = this.generateTaskDefinition();
            if (definition == null) {
                break;
            }
            this.putActiveTask(definition.id(), definition.create(this.player));
            this.timesGottenById.merge(definition.id(), 1, Integer::sum);
            syncLegacyTimesGotten(definition.id());
            assignedTasks.add(definition.id());
        }

        if (!assignedTasks.isEmpty()) {
            this.sync();
            this.dirty = false;
        }
        return List.copyOf(assignedTasks);
    }

    /**
     * 指定发放某个注册任务。
     *
     * <p>这个入口由 {@link MoodTaskApi} 调用。它不会检查任务是否在随机池中，
     * 因为扩展职业专属任务的默认语义就是“注册后只允许指定发放”。</p>
     */
    public boolean assignExternalTask(@NotNull Identifier taskId) {
        if (!this.canReceiveExternalMoodTask() || this.activeTasks.containsKey(taskId)) {
            return false;
        }

        MoodTaskDefinition definition = MoodTaskApi.getDefinition(taskId);
        if (definition == null) {
            return false;
        }

        this.putActiveTask(taskId, definition.create(this.player));
        this.sync();
        this.dirty = false;
        return true;
    }

    /**
     * 外部 API 的“只删除任务”入口。
     *
     * <p>删除不代表完成，因此不会加心情、不会给金币、不会写回放，也不会触发任务完成事件。</p>
     */
    public boolean removeExternalTask(@NotNull Identifier taskId) {
        boolean removed = this.removeActiveTask(taskId) != null;
        if (removed) {
            this.sync();
            this.dirty = false;
        }
        return removed;
    }

    /**
     * 外部 API 的“按完成语义完成任务”入口。
     */
    public boolean completeExternalTask(@NotNull Identifier taskId, boolean rewardMood) {
        boolean completed = this.completeTask(taskId, rewardMood);
        if (completed && this.dirty) {
            this.sync();
            this.dirty = false;
        }
        return completed;
    }

    /**
     * 某个任务正常完成后的统一处理。
     * 这里会负责：
     * 1. 移除任务本身；
     * 2. 给予心情奖励（仅真实心情角色）；
     * 3. 发送任务完成提示；
     * 4. 递增其他残留任务的“卡死累计计数”；
     * 5. 按规则自动清除卡死任务；
     * 6. 如果当前心情阈值仍然要求存在更多任务，则只补到“当前应有数量”为止。
     */
    private void completeTask(@NotNull Task taskType, boolean rewardMood) {
        this.completeTask(MoodTaskApi.getTaskId(taskType), rewardMood);
    }

    private boolean completeTask(@NotNull Identifier taskId, boolean rewardMood) {
        if (!this.activeTasks.containsKey(taskId)) {
            return false;
        }

        if (this.player instanceof ServerPlayerEntity serverPlayer) {
            GameWorldComponent gameWorld = GameWorldComponent.KEY.get(serverPlayer.getWorld());
            MoodTaskDefinition definition = MoodTaskApi.getDefinition(taskId);
            Task legacyTask = MoodTaskApi.getLegacyTask(taskId);
            MoodTaskApi.MoodTaskCompletionContext completionContext = new MoodTaskApi.MoodTaskCompletionContext(
                    serverPlayer,
                    gameWorld,
                    gameWorld.getRole(serverPlayer),
                    taskId,
                    definition,
                    legacyTask,
                    rewardMood
            );
            /*
             * 任务完成拦截放在真正移除任务之前。
             * 灵术师附身这类效果只是不允许“确认完成”，不应该破坏任务当前的进度状态。
             */
            if (!MoodTaskApi.canCompleteTask(completionContext)) {
                return false;
            }
        }

        this.removeActiveTask(taskId);

        if (rewardMood) {
            /**
             * 这里故意走公共 setMood，而不是内部静默分支。
             *
             * 原因是像 starryexpress 这类扩展职业模组，会直接 mixin 注入到
             * PlayerMoodComponent#setMood(float) 来监听“任务完成带来的心情回升”，
             * 并据此触发额外效果（例如缩减技能冷却）。
             *
             * 为了兼容这些旧扩展，任务完成时的加心情需要重新经过公共入口。
             */
            this.setMood(this.mood + GameConstants.MOOD_GAIN);
        }

        if (this.player instanceof ServerPlayerEntity serverPlayer) {
            ServerPlayNetworking.send(serverPlayer, new TaskCompletePayload());
            GameRecordManager.recordTaskComplete(serverPlayer, taskId);
            /*
             * 这是 Wathe 对外公开的“真实任务完成”事件点。
             * 旧扩展常用 setMood 注入或监听 TaskCompletePayload 来推断任务完成，
             * 但这两种方式都不是稳定语义：setMood 会被非任务来源触发，网络包监听又依赖内部同步细节。
             * 因此这里在任务已经移除、提示与回放都记录完之后统一分发 API 事件。
             */
            TaskCompletionApi.handleTaskCompleted(serverPlayer, GameWorldComponent.KEY.get(serverPlayer.getWorld()), taskId, MoodTaskApi.getLegacyTask(taskId), rewardMood);
        }

        this.incrementStuckTaskCounters();
        this.clearStuckTasksIfNeeded();

        if (this.activeTasks.isEmpty()) {
            this.nextTaskTimer = this.getRandomTaskCooldown();
        } else {
            this.fillTasksUpToExpectedCount(this.getExpectedConcurrentTaskCount(getCurrentRole()));
        }
        return true;
    }

    /**
     * 每当某个任务正常完成时，其余仍然挂在任务栏里的任务都会累计一次“别人完成次数”。
     * 谁一直赖在任务栏上，谁的计数就会一直上涨。
     */
    private void incrementStuckTaskCounters() {
        for (Identifier taskId : this.activeTasks.keySet()) {
            this.stuckTaskCompletionCounts.merge(taskId, 1, Integer::sum);
        }
        if (!this.activeTasks.isEmpty()) {
            this.markDirty();
        }
    }

    /**
     * 根据你的规则清除卡死任务：
     * 1. 如果只有 1 个长期残留任务达到 4 次累计，则删掉这 1 个；
     * 2. 如果有 2 个长期残留任务都达到 6 次累计，则一起删掉这 2 个；
     * 3. 自动删除时不加心情；
     * 4. 一旦触发删除，就把其余任务的累计清零，避免旧累计继续影响后续判断。
     */
    private void clearStuckTasksIfNeeded() {
        ArrayList<Identifier> twoStuckCandidates = new ArrayList<>();
        for (Identifier taskId : this.activeTasks.keySet()) {
            if (this.stuckTaskCompletionCounts.getOrDefault(taskId, 0) >= GameConstants.TASK_COMPLETIONS_TO_CLEAR_TWO_STUCK_TASKS) {
                twoStuckCandidates.add(taskId);
            }
        }

        if (twoStuckCandidates.size() >= 2) {
            for (Identifier taskId : twoStuckCandidates) {
                this.removeActiveTask(taskId);
            }
            this.resetRemainingStuckCounters();
            this.markDirty();
            return;
        }

        ArrayList<Identifier> oneStuckCandidates = new ArrayList<>();
        for (Identifier taskId : this.activeTasks.keySet()) {
            if (this.stuckTaskCompletionCounts.getOrDefault(taskId, 0) >= GameConstants.TASK_COMPLETIONS_TO_CLEAR_ONE_STUCK_TASK) {
                oneStuckCandidates.add(taskId);
            }
        }

        if (oneStuckCandidates.size() == 1) {
            Identifier taskId = oneStuckCandidates.get(0);
            this.removeActiveTask(taskId);
            this.resetRemainingStuckCounters();
            this.markDirty();
        }
    }

    /**
     * 当卡死任务被自动清掉后，其余任务的累计值要归零。
     * 这样后续重新生成的新任务会从一个干净的状态重新开始累计。
     */
    private void resetRemainingStuckCounters() {
        this.stuckTaskCompletionCounts.clear();
        for (Identifier taskId : this.activeTasks.keySet()) {
            this.stuckTaskCompletionCounts.put(taskId, 0);
        }
    }

    private List<Item> getPsychosisItemPool() {
        if (cachedPsychosisItems == null) {
            cachedPsychosisItems = this.player.getRegistryManager()
                    .createRegistryLookup()
                    .getOrThrow(RegistryKeys.ITEM)
                    .getOptional(WatheItemTags.PSYCHOSIS_ITEMS)
                    .map(RegistryEntryList.ListBacked::stream)
                    .map(stream -> stream.map(RegistryEntry::value).toList())
                    .orElseGet(() -> {
                        Wathe.LOGGER.error("Server provided empty tag {}", WatheItemTags.PSYCHOSIS_ITEMS.id());
                        return List.of();
                    });
        }
        return cachedPsychosisItems;
    }

    @Override
    public void clientTick() {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
        Role role = gameWorldComponent.getRole(this.player);
        if (!gameWorldComponent.isRunning() || !WatheClient.isPlayerAliveAndInSurvival()) {
            return;
        }

        this.tickExternalMoodDrainProtection(false);

        // 客户端做一次本地预测，让心情 HUD 的下降更平滑。
        // 掉速保持原版：只要身上有任务，就按单任务速度扣一次。
        if (hasRealMood(role) && !this.activeTasks.isEmpty() && !this.hasMoodDrainProtection()) {
            this.mood = MathHelper.clamp(this.mood - this.getCurrentMoodDrainPerTick(), 0f, 1f);
        }

        if (this.isLowerThanMid()) {
            // 心情低于中线后，会开始“脑补”其他玩家手里的危险物品。
            for (PlayerEntity playerEntity : this.player.getWorld().getPlayers()) {
                if (!playerEntity.equals(this.player) && this.player.getWorld().getRandom().nextInt(GameConstants.ITEM_PSYCHOSIS_REROLL_TIME) == 0) {
                    ItemStack psychosisStack;
                    List<Item> taggedItems = getPsychosisItemPool();

                    if (!taggedItems.isEmpty() && this.player.getRandom().nextFloat() < GameConstants.ITEM_PSYCHOSIS_CHANCE) {
                        Item item = Util.getRandom(taggedItems, this.player.getRandom());
                        psychosisStack = new ItemStack(item);
                    } else {
                        psychosisStack = playerEntity.getMainHandStack();
                    }

                    if (psychosisStack.getItem() instanceof ItemWithSkin) {
                        psychosisStack.set(WatheDataComponentTypes.OWNER, playerEntity.getUuidAsString());
                    }
                    this.psychosisItems.put(playerEntity.getUuid(), psychosisStack);
                }
            }
        } else if (!this.psychosisItems.isEmpty()) {
            this.psychosisItems.clear();
        }
    }

    @Override
    public void serverTick() {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
        if (!gameWorldComponent.isRunning() || !GameFunctions.isPlayerAliveAndSurvival(this.player)) {
            return;
        }

        this.tickExternalMoodDrainProtection(true);

        Role role = gameWorldComponent.getRole(this.player);
        boolean hasRealMood = hasRealMood(role);
        boolean hasFakeMood = hasFakeMood(role);
        if (!hasRealMood && !hasFakeMood) {
            return;
        }

        // 真实心情角色只要身上还有任务，就按原版单任务速度掉心情。
        if (hasRealMood && !this.activeTasks.isEmpty() && !this.hasMoodDrainProtection()) {
            this.setMoodSilently(this.mood - this.getCurrentMoodDrainPerTick());
        }

        if (hasRealMood) {
            /**
             * 多任务现在改成“严格按当前心情阈值补刷”：
             * 1. 心情继续处在低阈值区间时，会把缺的任务补回来；
             * 2. 心情回升到高阈值区间后，不会因为之前曾经低过而继续顶满任务数；
             * 3. 但已经挂在任务栏里的旧任务会保留，直到玩家自己完成或被卡死清理机制删除。
             */
            this.fillTasksUpToExpectedCount(this.getExpectedConcurrentTaskCount(role));
        }

        // 第一个任务槽位仍然走时间刷新机制。
        this.tickPrimaryTaskCooldown(hasRealMood);

        ArrayList<Identifier> completedTasks = new ArrayList<>();
        ArrayList<Map.Entry<Identifier, TrainTask>> currentTasks = new ArrayList<>(this.activeTasks.entrySet());
        for (Map.Entry<Identifier, TrainTask> entry : currentTasks) {
            TrainTask task = entry.getValue();
            task.tick(this.player);
            if (task.isFulfilled(this.player)) {
                completedTasks.add(entry.getKey());
            }
        }

        for (Identifier taskId : completedTasks) {
            this.completeTask(taskId, hasRealMood);
        }

        // 心情死亡机制开启时，真实心情归零会立刻精神崩溃死亡。
        if (hasRealMood && gameWorldComponent.isMoodEffectDeathEnabled() && this.mood <= 0f && GameFunctions.isPlayerAliveAndSurvival(this.player)) {
            GameFunctions.killPlayer(this.player, true, null, GameConstants.DeathReasons.MENTAL_BREAKDOWN);
            return;
        }

        if (this.dirty) {
            this.sync();
            this.dirty = false;
        }
    }

    /**
     * 随机生成一个新任务。
     * 这里依然沿用“出现次数越多，下一次权重越低”的抽取逻辑，只是不再限制同时只能存在一个任务。
     */
    private @Nullable MoodTaskDefinition generateTaskDefinition() {
        HashMap<Identifier, Float> weights = new HashMap<>();
        float total = 0f;

        for (MoodTaskDefinition definition : MoodTaskApi.getRandomAssignableDefinitions()) {
            Identifier taskId = definition.id();
            if (this.activeTasks.containsKey(taskId)) {
                continue;
            }

            float weight = definition.randomWeight() / Math.max(1, this.timesGottenById.getOrDefault(taskId, 0) + 1);
            weights.put(taskId, weight);
            total += weight;
        }

        if (total <= 0f) {
            return null;
        }

        float random = this.player.getRandom().nextFloat() * total;
        for (Map.Entry<Identifier, Float> entry : weights.entrySet()) {
            random -= entry.getValue();
            if (random <= 0f) {
                return MoodTaskApi.getDefinition(entry.getKey());
            }
        }

        return null;
    }

    public float getMood() {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
        Role role = gameWorldComponent.getRole(this.player);

        if (gameWorldComponent.isRunning() && hasRealMood(role)) {
            return this.mood;
        }
        return 1f;
    }

    public void eatFood() {
        if (this.tasks.get(Task.EAT) instanceof EatTask eatTask) {
            eatTask.fulfilled = true;
        }
    }

    public void drinkCocktail() {
        if (this.tasks.get(Task.DRINK) instanceof DrinkTask drinkTask) {
            drinkTask.fulfilled = true;
        }
    }

    /**
     * 喝药水任务的外部事件入口。
     * 只要消费的确实是 minecraft:potion，就把任务标记为完成。
     */
    public void drinkPotion() {
        if (this.tasks.get(Task.POTION) instanceof PotionTask potionTask) {
            potionTask.fulfilled = true;
        }
    }

    /**
     * 音符盒任务的外部事件入口。
     * 每次成功右键音符盒时累计 1 次。
     */
    public void playNoteBlock() {
        if (this.tasks.get(Task.MUSIC) instanceof MusicTask musicTask) {
            musicTask.incrementPlayCount();
        }
    }

    /**
     * 钓鱼任务的外部事件入口。
     * 只有真正把战利品勾回来时才会调用。
     */
    public void catchFish() {
        if (this.tasks.get(Task.FISH) instanceof FishTask fishTask) {
            fishTask.fulfilled = true;
        }
    }

    /**
     * “烤吃的”任务的外部事件入口。
     * 外部事件会先严格判断：
     * 1. 玩家确实是从普通熔炉 / 烟熏炉结果槽里取出物品；
     * 2. 取出的结果本身是可食用物品。
     *
     * <p>只有满足这些前置条件时才会调用这里，因此这里可以直接把任务标记完成。
     */
    public void takeCookedFood() {
        if (this.tasks.get(Task.COOK) instanceof CookTask cookTask) {
            cookTask.fulfilled = true;
        }
    }

    public boolean isLowerThanMid() {
        return this.getMood() < GameConstants.MID_MOOD_THRESHOLD;
    }

    public boolean isLowerThanDepressed() {
        return this.getMood() <= GameConstants.DEPRESSIVE_MOOD_THRESHOLD;
    }

    public HashMap<UUID, ItemStack> getPsychosisItems() {
        return this.psychosisItems;
    }

    /**
     * 判断玩家视线前方一定距离内，是否真正看到了另一名存活玩家。
     * 直接复用碰撞检测，因此天然具备“不能隔墙看人也算完成”的效果。
     */
    private static boolean isLookingAtAlivePlayer(@NotNull PlayerEntity player, float range) {
        return ProjectileUtil.getCollision(
                player,
                entity -> entity instanceof PlayerEntity target
                        && !target.equals(player)
                        && GameFunctions.isPlayerAliveAndSurvival(target),
                range
        ) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof PlayerEntity;
    }

    /**
     * 判断玩家附近一定半径内是否存在其他仍在局内的存活玩家。
     */
    private static boolean hasNearbyAlivePlayer(@NotNull PlayerEntity player, float range) {
        return hasNearbyAlivePlayer(player, range, false);
    }

    private static boolean hasNearbyAlivePlayer(
            @NotNull PlayerEntity player,
            float range,
            boolean ignoreSpectatorAliveOverride
    ) {
        double rangeSquared = range * range;
        for (PlayerEntity other : player.getWorld().getPlayers()) {
            if (other.equals(player) || !GameFunctions.isPlayerAliveAndSurvival(other)) {
                continue;
            }
            /*
             * 这里只在 AWAY 任务里额外忽略“spectator 但仍被标记为玩法存活”的玩家。
             *
             * 这样不会改动 Wathe 的全局存活定义，也不会影响胜负、结算、任务刷新等其它系统；
             * 只是让这一个任务不再把双重人格的休眠人格当作阻止独处的近邻。
             */
            if (ignoreSpectatorAliveOverride
                    && other.isSpectator()
                    && PlayerLifeStateApi.hasAliveOverride(other)) {
                continue;
            }
            if (other.squaredDistanceTo(player) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断玩家是否处于“烤火任务认可的热源”半径范围内。
     *
     * <p>当前认可两种热源：
     * 1. {@link Blocks#FIRE}，也就是打火石点出来的普通火焰；
     * 2. {@link Blocks#CAMPFIRE}，并且必须是“已点燃”状态的营火。
     *
     * <p>这样就能满足你现在的需求：
     * 普通火焰旁边可以完成任务，点燃的营火旁边也可以完成任务，
     * 但被熄灭的营火、灵魂火、火把等其它方块都不会被误判进去。
     *
     * <p>实现上先在玩家周围一个很小的立方体范围内找候选热源，再用真实距离平方过滤，
     * 这样既能表达“半径 2 格内”的效果，也方便以后只在常量里改范围。
     */
    private static boolean isNearWarmFireSource(@NotNull PlayerEntity player, float range) {
        int blockRange = (int) Math.ceil(range);
        double rangeSquared = range * range;
        double playerCenterX = player.getX();
        double playerCenterY = player.getY() + player.getHeight() * 0.5;
        double playerCenterZ = player.getZ();

        for (BlockPos pos : BlockPos.iterateOutwards(player.getBlockPos(), blockRange, blockRange, blockRange)) {
            var blockState = player.getWorld().getBlockState(pos);

            boolean isNormalFire = blockState.isOf(Blocks.FIRE);
            boolean isLitCampfire = blockState.isOf(Blocks.CAMPFIRE)
                    && blockState.contains(CampfireBlock.LIT)
                    && blockState.get(CampfireBlock.LIT);

            if (!isNormalFire && !isLitCampfire) {
                continue;
            }

            double deltaX = playerCenterX - (pos.getX() + 0.5);
            double deltaY = playerCenterY - (pos.getY() + 0.5);
            double deltaZ = playerCenterZ - (pos.getZ() + 0.5);
            double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (distanceSquared <= rangeSquared) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断玩家是否坐在本模组的座位实体上。
     */
    private static boolean isSittingOnSeat(@NotNull PlayerEntity player) {
        return player.hasVehicle() && player.getVehicle() instanceof SeatEntity;
    }

    /**
     * 判断玩家是否正在查看讲台上的书与笔 / 成书界面。
     */
    private static boolean isReadingLecternBook(@NotNull PlayerEntity player) {
        if (!(player.currentScreenHandler instanceof LecternScreenHandler lecternScreenHandler)) {
            return false;
        }

        ItemStack bookStack = lecternScreenHandler.getBookItem();
        return bookStack.isOf(Items.WRITABLE_BOOK) || bookStack.isOf(Items.WRITTEN_BOOK);
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        tag.putFloat("mood", this.mood);
        tag.putInt("nextTaskTimer", this.nextTaskTimer);
        tag.putFloat("externalMoodDrainMultiplier", this.externalMoodDrainMultiplier);
        tag.putInt("externalMoodDrainProtectionTicks", this.externalMoodDrainProtectionTicks);

        NbtList tasks = new NbtList();
        for (Map.Entry<Identifier, TrainTask> entry : this.activeTasks.entrySet()) {
            NbtCompound taskTag = entry.getValue().toNbt();
            /*
             * 新任务存档统一写稳定 Identifier。
             * 内置任务自己的 toNbt 仍会写旧 type ordinal，用于兼容旧版读取；
             * 扩展任务只需要写自己的运行时字段，任务类型由这里的 id 负责保存。
             */
            taskTag.putString("id", entry.getKey().toString());
            tasks.add(taskTag);
        }
        tag.put("tasks", tasks);

        NbtCompound timesTag = new NbtCompound();
        for (Map.Entry<Identifier, Integer> entry : this.timesGottenById.entrySet()) {
            timesTag.putInt(entry.getKey().toString(), entry.getValue());
            Task legacyTask = MoodTaskApi.getLegacyTask(entry.getKey());
            if (legacyTask != null) {
                timesTag.putInt(legacyTask.name(), entry.getValue());
            }
        }
        tag.put("timesGotten", timesTag);

        NbtCompound stuckCountersTag = new NbtCompound();
        for (Map.Entry<Identifier, Integer> entry : this.stuckTaskCompletionCounts.entrySet()) {
            stuckCountersTag.putInt(entry.getKey().toString(), entry.getValue());
            Task legacyTask = MoodTaskApi.getLegacyTask(entry.getKey());
            if (legacyTask != null) {
                stuckCountersTag.putInt(legacyTask.name(), entry.getValue());
            }
        }
        tag.put("stuckTaskCompletionCounts", stuckCountersTag);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        this.mood = tag.contains("mood", NbtElement.FLOAT_TYPE) ? tag.getFloat("mood") : 1f;
        this.nextTaskTimer = tag.contains("nextTaskTimer", NbtElement.INT_TYPE) ? tag.getInt("nextTaskTimer") : GameConstants.TIME_TO_FIRST_TASK;
        this.externalMoodDrainMultiplier = tag.contains("externalMoodDrainMultiplier", NbtElement.FLOAT_TYPE)
                ? Math.max(0f, tag.getFloat("externalMoodDrainMultiplier"))
                : 1f;
        this.externalMoodDrainProtectionTicks = tag.contains("externalMoodDrainProtectionTicks", NbtElement.INT_TYPE)
                ? Math.max(0, tag.getInt("externalMoodDrainProtectionTicks"))
                : 0;
        /**
         * 旧版本存档里可能还会带着 unlockedTaskSlots。
         * 现在该字段已经废弃，因为多任务数量不再是“本局永久解锁”，
         * 而是每个 tick 都按当前心情阈值实时判定，所以这里直接忽略旧字段即可。
         */
        this.dirty = false;

        this.activeTasks.clear();
        this.tasks.clear();
        if (tag.contains("tasks", NbtElement.LIST_TYPE)) {
            for (NbtElement element : tag.getList("tasks", NbtElement.COMPOUND_TYPE)) {
                if (!(element instanceof NbtCompound compound)) {
                    continue;
                }

                Identifier taskId = readTaskId(compound);
                MoodTaskDefinition definition = taskId == null ? null : MoodTaskApi.getDefinition(taskId);
                if (taskId != null && definition != null) {
                    this.putActiveTask(taskId, definition.read(this.player, compound), false);
                }
            }
        }

        this.timesGottenById.clear();
        this.timesGotten.clear();
        if (tag.contains("timesGotten", NbtElement.COMPOUND_TYPE)) {
            NbtCompound timesTag = tag.getCompound("timesGotten");
            for (String key : timesTag.getKeys()) {
                Identifier taskId = readTaskId(key);
                if (taskId != null && timesTag.contains(key, NbtElement.INT_TYPE)) {
                    this.timesGottenById.put(taskId, timesTag.getInt(key));
                    syncLegacyTimesGotten(taskId);
                }
            }
        }

        this.stuckTaskCompletionCounts.clear();
        if (tag.contains("stuckTaskCompletionCounts", NbtElement.COMPOUND_TYPE)) {
            NbtCompound stuckCountersTag = tag.getCompound("stuckTaskCompletionCounts");
            for (String key : stuckCountersTag.getKeys()) {
                Identifier taskId = readTaskId(key);
                if (taskId != null && stuckCountersTag.contains(key, NbtElement.INT_TYPE)) {
                    this.stuckTaskCompletionCounts.put(taskId, stuckCountersTag.getInt(key));
                }
            }
        }

        // 为了兼容旧存档或缺失字段的情况，确保现存任务一定有对应的累计计数槽。
        for (Identifier taskId : this.activeTasks.keySet()) {
            this.stuckTaskCompletionCounts.putIfAbsent(taskId, 0);
        }
    }

    private static @Nullable Identifier readTaskId(@NotNull NbtCompound taskTag) {
        if (taskTag.contains("id", NbtElement.STRING_TYPE)) {
            return readTaskId(taskTag.getString("id"));
        }

        if (taskTag.contains("type", NbtElement.INT_TYPE)) {
            int type = taskTag.getInt("type");
            if (type >= 0 && type < Task.values().length) {
                return MoodTaskApi.getTaskId(Task.values()[type]);
            }
        }
        return null;
    }

    private static @Nullable Identifier readTaskId(@NotNull String serializedId) {
        Identifier parsed = Identifier.tryParse(serializedId);
        if (parsed != null && MoodTaskApi.isRegistered(parsed)) {
            return parsed;
        }

        try {
            return MoodTaskApi.getTaskId(Task.valueOf(serializedId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public enum Task {
        SLEEP(nbt -> new SleepTask(nbt.getInt("timer"))),
        OUTSIDE(nbt -> new OutsideTask(nbt.getInt("timer"))),
        WATER(nbt -> new WaterTask(nbt.getInt("timer"))),
        SHIFT(nbt -> new ShiftTask(nbt.getInt("timer"))),
        STARE(nbt -> new StareTask(nbt.getInt("timer"))),
        AWAY(nbt -> new AwayTask(nbt.getInt("timer"))),
        EAT(nbt -> new EatTask(nbt.getBoolean("fulfilled"))),
        DRINK(nbt -> new DrinkTask(nbt.getBoolean("fulfilled"))),
        RUN(nbt -> new RunTask(nbt.getInt("timer"))),
        SIT(nbt -> new SitTask(nbt.getInt("timer"))),
        POTION(nbt -> new PotionTask(nbt.getBoolean("fulfilled"))),
        MUSIC(nbt -> new MusicTask(nbt.getInt("count"))),
        BOOK(nbt -> new BookTask(nbt.getInt("timer"))),
        STAY(nbt -> new StayTask(nbt.getInt("timer"))),
        FISH(nbt -> new FishTask(nbt.getBoolean("fulfilled"))),
        /**
         * 新任务统一追加到枚举末尾，避免打乱旧存档里通过 ordinal 持久化的任务编号。
         */
        FIRE(nbt -> new FireTask(nbt.getInt("timer"))),
        COOK(nbt -> new CookTask(nbt.getBoolean("fulfilled")));

        public final @NotNull Function<NbtCompound, TrainTask> setFunction;

        Task(@NotNull Function<NbtCompound, TrainTask> function) {
            this.setFunction = function;
        }
    }

    /**
     * 所有“累计时长型任务”的公共父类。
     * 只要条件满足就递减倒计时；条件中断时只是暂停，不会清零。
     */
    public abstract static class AccumulatingTimedTask implements TrainTask {
        protected int timer;

        protected AccumulatingTimedTask(int time) {
            this.timer = time;
        }

        @Override
        public void tick(@NotNull PlayerEntity player) {
            if (this.timer > 0 && this.shouldCount(player)) {
                this.timer--;
            }
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.timer <= 0;
        }

        /**
         * 由子类决定“这一 tick 是否应该累计进度”。
         */
        protected abstract boolean shouldCount(@NotNull PlayerEntity player);

        protected @NotNull NbtCompound createTimedTaskNbt(@NotNull Task task) {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", task.ordinal());
            nbt.putInt("timer", this.timer);
            return nbt;
        }
    }

    public static class SleepTask extends AccumulatingTimedTask {
        public SleepTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return player.isSleeping();
        }

        @Override
        public String getName() {
            return "sleep";
        }

        @Override
        public Task getType() {
            return Task.SLEEP;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.SLEEP);
        }
    }

    public static class OutsideTask extends AccumulatingTimedTask {
        public OutsideTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return isSkyVisibleAdjacent(player);
        }

        @Override
        public String getName() {
            return "outside";
        }

        @Override
        public Task getType() {
            return Task.OUTSIDE;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.OUTSIDE);
        }
    }

    /**
     * 泡水任务：只要玩家在水里，就累计进度。
     */
    public static class WaterTask extends AccumulatingTimedTask {
        public WaterTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return player.isTouchingWater();
        }

        @Override
        public String getName() {
            return "water";
        }

        @Override
        public Task getType() {
            return Task.WATER;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.WATER);
        }
    }

    /**
     * 烤火任务：只要玩家一直待在有效热源半径范围内，就持续累计进度。
     *
     * <p>和其它累计计时任务一样，这里只会“暂停累计”，不会在离开范围时把进度清零，
     * 对应你要求的“秒数累计不中断”。
     *
     * <p>当前有效热源包括：
     * 1. 打火石点出来的普通火焰；
     * 2. 处于点燃状态的营火。
     */
    public static class FireTask extends AccumulatingTimedTask {
        public FireTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return isNearWarmFireSource(player, GameConstants.FIRE_TASK_RANGE);
        }

        @Override
        public String getName() {
            return "fire";
        }

        @Override
        public Task getType() {
            return Task.FIRE;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.FIRE);
        }
    }

    /**
     * 蹲下任务：只要玩家在潜行 / 蹲下，就累计进度。
     */
    public static class ShiftTask extends AccumulatingTimedTask {
        public ShiftTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return player.isSneaking();
        }

        @Override
        public String getName() {
            return "shift";
        }

        @Override
        public Task getType() {
            return Task.SHIFT;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.SHIFT);
        }
    }

    /**
     * 凝视任务：需要在 2 格内真正盯着另一名存活玩家，且不能穿墙。
     */
    public static class StareTask extends AccumulatingTimedTask {
        public StareTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return isLookingAtAlivePlayer(player, GameConstants.STARE_TASK_RANGE);
        }

        @Override
        public String getName() {
            return "stare";
        }

        @Override
        public Task getType() {
            return Task.STARE;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.STARE);
        }
    }

    /**
     * 远离任务：只要 12 格内没有其他仍在局内的存活玩家，就累计进度。
     */
    public static class AwayTask extends AccumulatingTimedTask {
        public AwayTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            /*
             * 这里要额外忽略“双重人格休眠人格”这类：
             * 客户端表现为旁观，但 Wathe 的特殊存活状态仍把他算作局内存活玩家。
             *
             * 如果不在这里单独跳过这类目标，活跃人格身边只要还挂着自己的休眠人格，
             * “离人远一点”就会永远算不到空旷状态。
             */
            return !hasNearbyAlivePlayer(player, GameConstants.AWAY_TASK_RANGE, true);
        }

        @Override
        public String getName() {
            return "away";
        }

        @Override
        public Task getType() {
            return Task.AWAY;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.AWAY);
        }
    }

    /**
     * 跑步任务：只要玩家处于疾跑状态，就累计进度。
     */
    public static class RunTask extends AccumulatingTimedTask {
        public RunTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return player.isSprinting();
        }

        @Override
        public String getName() {
            return "run";
        }

        @Override
        public Task getType() {
            return Task.RUN;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.RUN);
        }
    }

    /**
     * 坐下任务：检测玩家是否坐在本模组的座位实体上。
     */
    public static class SitTask extends AccumulatingTimedTask {
        public SitTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return isSittingOnSeat(player);
        }

        @Override
        public String getName() {
            return "sit";
        }

        @Override
        public Task getType() {
            return Task.SIT;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.SIT);
        }
    }

    /**
     * 看书任务：只在打开讲台上的书与笔 / 成书界面时累计时间。
     */
    public static class BookTask extends AccumulatingTimedTask {
        public BookTask(int time) {
            super(time);
        }

        @Override
        protected boolean shouldCount(@NotNull PlayerEntity player) {
            return isReadingLecternBook(player);
        }

        @Override
        public String getName() {
            return "book";
        }

        @Override
        public Task getType() {
            return Task.BOOK;
        }

        @Override
        public NbtCompound toNbt() {
            return this.createTimedTaskNbt(Task.BOOK);
        }
    }

    /**
     * 静止任务：要求玩家保持原地不动且不处于跳跃/下落中。
     * 转视角不算移动，因此不会影响任务推进。
     */
    public static class StayTask implements TrainTask {
        private static final double MOVEMENT_EPSILON_SQUARED = 1.0E-4;

        private int timer;
        private double lastX;
        private double lastY;
        private double lastZ;
        private boolean hasLastPosition;

        public StayTask(int time) {
            this.timer = time;
        }

        @Override
        public void tick(@NotNull PlayerEntity player) {
            boolean shouldCount = false;

            if (this.hasLastPosition) {
                double deltaX = player.getX() - this.lastX;
                double deltaY = player.getY() - this.lastY;
                double deltaZ = player.getZ() - this.lastZ;
                double movementSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;

                boolean stayedAtSamePlace = movementSquared <= MOVEMENT_EPSILON_SQUARED;
                boolean notJumpingOrFalling = player.hasVehicle() || player.isOnGround();
                shouldCount = stayedAtSamePlace && notJumpingOrFalling;
            }

            this.lastX = player.getX();
            this.lastY = player.getY();
            this.lastZ = player.getZ();
            this.hasLastPosition = true;

            if (this.timer > 0 && shouldCount) {
                this.timer--;
            }
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.timer <= 0;
        }

        @Override
        public String getName() {
            return "stay";
        }

        @Override
        public Task getType() {
            return Task.STAY;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.STAY.ordinal());
            nbt.putInt("timer", this.timer);
            return nbt;
        }
    }

    public static class EatTask implements TrainTask {
        public boolean fulfilled;

        public EatTask() {
            this(false);
        }

        public EatTask(boolean fulfilled) {
            this.fulfilled = fulfilled;
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.fulfilled;
        }

        @Override
        public String getName() {
            return "eat";
        }

        @Override
        public Task getType() {
            return Task.EAT;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.EAT.ordinal());
            nbt.putBoolean("fulfilled", this.fulfilled);
            return nbt;
        }
    }

    public static class DrinkTask implements TrainTask {
        public boolean fulfilled;

        public DrinkTask() {
            this(false);
        }

        public DrinkTask(boolean fulfilled) {
            this.fulfilled = fulfilled;
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.fulfilled;
        }

        @Override
        public String getName() {
            return "drink";
        }

        @Override
        public Task getType() {
            return Task.DRINK;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.DRINK.ordinal());
            nbt.putBoolean("fulfilled", this.fulfilled);
            return nbt;
        }
    }

    /**
     * 烤吃的任务：由炉子结果槽事件在玩家真正取出熟食时直接标记完成。
     *
     * <p>这里不自己扫描熔炉方块实体，是因为任务要求的关键动作不是“炉子烧好了”，
     * 而是“玩家把熟食从结果槽中拿了出来”。这个动作由结果槽 mixin 处理最准确。
     */
    public static class CookTask implements TrainTask {
        public boolean fulfilled;

        public CookTask() {
            this(false);
        }

        public CookTask(boolean fulfilled) {
            this.fulfilled = fulfilled;
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.fulfilled;
        }

        @Override
        public String getName() {
            return "cook";
        }

        @Override
        public Task getType() {
            return Task.COOK;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.COOK.ordinal());
            nbt.putBoolean("fulfilled", this.fulfilled);
            return nbt;
        }
    }

    /**
     * 喝药水任务：由外部事件直接标记完成。
     */
    public static class PotionTask implements TrainTask {
        public boolean fulfilled;

        public PotionTask() {
            this(false);
        }

        public PotionTask(boolean fulfilled) {
            this.fulfilled = fulfilled;
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.fulfilled;
        }

        @Override
        public String getName() {
            return "potion";
        }

        @Override
        public Task getType() {
            return Task.POTION;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.POTION.ordinal());
            nbt.putBoolean("fulfilled", this.fulfilled);
            return nbt;
        }
    }

    /**
     * 音符盒任务：统计成功右键音符盒的累计次数。
     */
    public static class MusicTask implements TrainTask {
        private int count;

        public MusicTask() {
            this(0);
        }

        public MusicTask(int count) {
            this.count = count;
        }

        public void incrementPlayCount() {
            if (this.count < GameConstants.MUSIC_TASK_COUNT) {
                this.count++;
            }
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.count >= GameConstants.MUSIC_TASK_COUNT;
        }

        @Override
        public String getName() {
            return "music";
        }

        @Override
        public Task getType() {
            return Task.MUSIC;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.MUSIC.ordinal());
            nbt.putInt("count", this.count);
            return nbt;
        }
    }

    /**
     * 钓鱼任务：在真正收回战利品时由外部事件标记完成。
     */
    public static class FishTask implements TrainTask {
        public boolean fulfilled;

        public FishTask() {
            this(false);
        }

        public FishTask(boolean fulfilled) {
            this.fulfilled = fulfilled;
        }

        @Override
        public boolean isFulfilled(@NotNull PlayerEntity player) {
            return this.fulfilled;
        }

        @Override
        public String getName() {
            return "fish";
        }

        @Override
        public Task getType() {
            return Task.FISH;
        }

        @Override
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("type", Task.FISH.ordinal());
            nbt.putBoolean("fulfilled", this.fulfilled);
            return nbt;
        }
    }

    public interface TrainTask {
        default void tick(@NotNull PlayerEntity player) {
        }

        boolean isFulfilled(PlayerEntity player);

        /**
         * 任务注册 id。
         *
         * <p>新扩展任务应优先覆写这个方法，返回自己注册到 {@link MoodTaskApi} 的 id。
         * 旧内置任务没有这个方法时，会继续通过 {@link #getType()} 映射到 Wathe 的内置 id。</p>
         */
        default @NotNull Identifier getId() {
            Task legacyTask = this.getType();
            return legacyTask == null ? Wathe.id("unknown") : MoodTaskApi.getTaskId(legacyTask);
        }

        /**
         * 旧版任务短名。
         *
         * <p>HUD 已经改为读取任务定义里的 translation key。
         * 这里保留默认实现，是为了旧扩展仍然能调用 {@code getName()} 调试内置任务。</p>
         */
        default String getName() {
            Identifier id = this.getId();
            String path = id.getPath();
            return path.isEmpty() ? "unknown" : path;
        }

        /**
         * 旧 enum 任务类型。
         *
         * <p>扩展新任务没有对应 enum，可以返回 null；所有新逻辑都应使用 {@link #getId()}。</p>
         */
        @Nullable
        default Task getType() {
            return MoodTaskApi.getLegacyTask(this.getId());
        }

        NbtCompound toNbt();
    }
}
