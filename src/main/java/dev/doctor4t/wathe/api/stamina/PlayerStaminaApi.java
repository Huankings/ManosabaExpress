package dev.doctor4t.wathe.api.stamina;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerStaminaComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家体力公开 API。
 *
 * <p>扩展职业需要清空体力、回满体力、增减当前体力或临时调整体力上限时，应统一走这里。
 * 不要再读取玩家 NBT 里的旧 {@code sprintingTicks}，也不要 mixin Wathe 的玩家移动逻辑。</p>
 */
public final class PlayerStaminaApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<ConsumptionRuleEntry> CONSUMPTION_RULE_COMPARATOR =
            Comparator.comparingInt(ConsumptionRuleEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ConsumptionRuleEntry::order).reversed());
    private static final List<ConsumptionRuleEntry> CONSUMPTION_RULES = new ArrayList<>();
    private static long nextConsumptionRuleOrder = 0L;

    private PlayerStaminaApi() {
    }

    /**
     * 注册一条“本次正常体力消耗是否应当执行”的规则。
     *
     * <p>这个入口和 {@link #drainStamina(PlayerEntity, float)} 的职责刻意分开：</p>
     * <p>1. 疾跑、低心情走路和扩展主动声明的能力成本，应调用受规则控制的
     * {@link #consumeStamina(PlayerEntity, float, StaminaConsumptionCause)}；</p>
     * <p>2. 管理员调试、惩罚技能或明确要求无视免消耗状态的逻辑，仍然可以调用
     * {@code drainStamina(...)} 强制扣除。</p>
     *
     * <p>priority 越大越先执行；同 priority 下后注册的规则先执行。同一个 id 重复注册会替换旧规则，
     * 方便扩展模组在配置重载或兼容层初始化时安全重复调用。</p>
     */
    public static void registerConsumptionRule(
            @NotNull Identifier id,
            int priority,
            @NotNull StaminaConsumptionRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (CONSUMPTION_RULES) {
            CONSUMPTION_RULES.removeIf(entry -> entry.id().equals(id));
            CONSUMPTION_RULES.add(new ConsumptionRuleEntry(id, priority, nextConsumptionRuleOrder++, rule));
            CONSUMPTION_RULES.sort(CONSUMPTION_RULE_COMPARATOR);
        }
    }

    public static @NotNull PlayerStaminaComponent getComponent(@NotNull PlayerEntity player) {
        return PlayerStaminaComponent.KEY.get(player);
    }

    public static float getStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getStamina();
    }

    public static void setStamina(@NotNull PlayerEntity player, float stamina) {
        getComponent(player).setStamina(stamina);
    }

    public static void addStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addStamina(amount);
    }

    public static void drainStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).drainStamina(amount);
    }

    /**
     * 尝试执行一次允许被扩展规则拦截的体力消耗。
     *
     * <p>返回 {@code true} 表示本次请求已经进入组件扣除流程；返回 {@code false} 表示数值无效，
     * 或某条高优先级规则返回了 {@link StaminaConsumptionResult#SKIP}。即使玩家当前体力不足，
     * 组件也会继续负责把结果安全钳制到 0。</p>
     */
    public static boolean consumeStamina(
            @NotNull PlayerEntity player,
            float amount,
            @NotNull StaminaConsumptionCause cause
    ) {
        if (!shouldConsumeStamina(player, amount, cause)) {
            return false;
        }
        getComponent(player).drainStamina(amount);
        return true;
    }

    /**
     * Wathe 移动 tick 使用的低频同步版本。
     *
     * <p>规则解析与公开 {@link #consumeStamina(PlayerEntity, float, StaminaConsumptionCause)} 完全相同，
     * 区别仅在于连续移动时先标记组件为脏，再由组件按固定周期同步，避免每 tick 给客户端发送体力包。</p>
     */
    public static boolean consumeStaminaFromServerTick(
            @NotNull PlayerEntity player,
            float amount,
            @NotNull StaminaConsumptionCause cause
    ) {
        if (!shouldConsumeStamina(player, amount, cause)) {
            return false;
        }
        getComponent(player).drainStaminaFromServerTick(amount);
        return true;
    }

    public static void clearStamina(@NotNull PlayerEntity player) {
        getComponent(player).clearStamina();
    }

    public static void fillStamina(@NotNull PlayerEntity player) {
        getComponent(player).fillStamina();
    }

    public static float getBaseMaxStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getBaseMaxStamina();
    }

    public static float getMaxStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getMaxStamina();
    }

    public static boolean hasFiniteStaminaLimit(@NotNull PlayerEntity player) {
        return getComponent(player).hasFiniteStaminaLimit();
    }

    public static float getMaxStaminaBonus(@NotNull PlayerEntity player) {
        return getComponent(player).getMaxStaminaBonus();
    }

    public static void setMaxStaminaBonus(@NotNull PlayerEntity player, float bonus) {
        getComponent(player).setMaxStaminaBonus(bonus);
    }

    public static void addMaxStaminaBonus(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(amount);
    }

    public static void increaseMaxStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(Math.max(0.0F, amount));
    }

    public static void decreaseMaxStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(-Math.max(0.0F, amount));
    }

    public static void resetMaxStaminaBonus(@NotNull PlayerEntity player) {
        getComponent(player).resetMaxStaminaBonus();
    }

    public static boolean isExhausted(@NotNull PlayerEntity player) {
        return isStaminaMechanicsActive(player) && getComponent(player).isExhausted();
    }

    /**
     * 判断当前玩家是否处于 Wathe 的局内体力管控范围。
     *
     * <p>只有“对局运行中 + 玩法存活 + 已经分配职业”的玩家才会被体力限制移动。
     * 这样大厅、旁观、创造、未入局玩家和刚进入服务器但尚未分配身份的玩家不会被 0 体力误锁。</p>
     */
    public static boolean isStaminaMechanicsActive(@NotNull PlayerEntity player) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        return gameWorld.isRunning()
                && GameFunctions.isPlayerAliveAndSurvival(player)
                && gameWorld.getRole(player) != null;
    }

    /**
     * 按当前世界开关与心情值解析体力惩罚档位。
     *
     * <p>规则顺序与用户需求一致：
     * 1. 两个开关默认关闭，此时所有心情都走 HIGH；
     * 2. 只开启中等惩罚时，中等到低落心情全部走 MID；
     * 3. 开启低落惩罚时，心情到达并低于 {@link GameConstants#DEPRESSIVE_MOOD_THRESHOLD} 走 DEPRESSIVE；
     * 4. 中等阈值使用“低于”判断，低落阈值使用“到达并低于”判断。</p>
     */
    public static @NotNull MoodPenaltyProfile resolveMoodPenaltyProfile(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return MoodPenaltyProfile.HIGH;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        float mood = PlayerMoodComponent.KEY.get(player).getMood();
        if (mood <= GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
            if (gameWorld.isDepressiveMoodStaminaPenaltyEnabled()) {
                return MoodPenaltyProfile.DEPRESSIVE;
            }
            return gameWorld.isMidMoodStaminaPenaltyEnabled() ? MoodPenaltyProfile.MID : MoodPenaltyProfile.HIGH;
        }
        if (mood < GameConstants.MID_MOOD_THRESHOLD && gameWorld.isMidMoodStaminaPenaltyEnabled()) {
            return MoodPenaltyProfile.MID;
        }
        return MoodPenaltyProfile.HIGH;
    }

    public static float getSprintDrainPerTick(@NotNull PlayerEntity player) {
        return switch (resolveMoodPenaltyProfile(player)) {
            case HIGH -> GameConstants.STAMINA_SPRINT_DRAIN_HIGH_MOOD;
            case MID -> GameConstants.STAMINA_SPRINT_DRAIN_MID_MOOD;
            case DEPRESSIVE -> GameConstants.STAMINA_SPRINT_DRAIN_DEPRESSIVE_MOOD;
        };
    }

    public static float getWalkDrainPerTick(@NotNull PlayerEntity player) {
        return resolveMoodPenaltyProfile(player) == MoodPenaltyProfile.DEPRESSIVE
                ? GameConstants.STAMINA_WALK_DRAIN_DEPRESSIVE_MOOD
                : 0.0F;
    }

    public static float getRecoveryPerTick(@NotNull PlayerEntity player) {
        return switch (resolveMoodPenaltyProfile(player)) {
            case HIGH -> GameConstants.STAMINA_RECOVERY_HIGH_MOOD;
            case MID -> GameConstants.STAMINA_RECOVERY_MID_MOOD;
            case DEPRESSIVE -> GameConstants.STAMINA_RECOVERY_DEPRESSIVE_MOOD;
        };
    }

    /**
     * 判断玩家是否仍允许自主疾跑。
     *
     * <p>低落惩罚开启并生效时会直接禁止疾跑；其它档位只在有限体力归零时禁止疾跑。</p>
     */
    public static boolean canSprint(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return true;
        }
        if (resolveMoodPenaltyProfile(player) == MoodPenaltyProfile.DEPRESSIVE) {
            return false;
        }
        PlayerStaminaComponent stamina = getComponent(player);
        return !stamina.hasFiniteStaminaLimit() || stamina.getStamina() > 0.0F;
    }

    /**
     * 判断玩家是否允许水平自主移动。
     *
     * <p>体力归零只会拦截玩家自己的水平移动输入；击退、传送、外部推力等外力位移不在这里处理。</p>
     */
    public static boolean canSelfMove(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return true;
        }
        PlayerStaminaComponent stamina = getComponent(player);
        return !stamina.hasFiniteStaminaLimit() || stamina.getStamina() > 0.0F;
    }

    public static boolean canJump(@NotNull PlayerEntity player) {
        return canSelfMove(player);
    }

    private static boolean shouldConsumeStamina(
            @NotNull PlayerEntity player,
            float amount,
            @NotNull StaminaConsumptionCause cause
    ) {
        Objects.requireNonNull(cause, "cause");
        if (!Float.isFinite(amount) || amount <= 0.0F) {
            return false;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        PlayerStaminaComponent stamina = getComponent(player);
        StaminaConsumptionContext context = new StaminaConsumptionContext(
                player,
                gameWorld,
                gameWorld.getRole(player),
                stamina,
                resolveMoodPenaltyProfile(player),
                cause,
                amount,
                stamina.getStamina(),
                stamina.getMaxStamina()
        );

        for (ConsumptionRuleEntry entry : consumptionRuleSnapshot()) {
            StaminaConsumptionResult result = entry.rule().resolve(context);
            if (result == StaminaConsumptionResult.CONSUME) {
                return true;
            }
            if (result == StaminaConsumptionResult.SKIP) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull List<ConsumptionRuleEntry> consumptionRuleSnapshot() {
        synchronized (CONSUMPTION_RULES) {
            return List.copyOf(CONSUMPTION_RULES);
        }
    }

    @FunctionalInterface
    public interface StaminaConsumptionRule {
        /**
         * @return PASS 继续询问后续规则；CONSUME 强制执行本次正常消耗；SKIP 跳过本次正常消耗。
         */
        @Nullable StaminaConsumptionResult resolve(@NotNull StaminaConsumptionContext context);
    }

    public record StaminaConsumptionContext(
            @NotNull PlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @NotNull PlayerStaminaComponent stamina,
            @NotNull MoodPenaltyProfile moodPenaltyProfile,
            @NotNull StaminaConsumptionCause cause,
            float requestedAmount,
            float currentStamina,
            float maxStamina
    ) {
    }

    public enum StaminaConsumptionCause {
        /** Wathe 自主疾跑的持续消耗。 */
        SPRINT,
        /** 低落心情惩罚开启后，自主走路产生的持续消耗。 */
        WALK,
        /** 扩展模组主动声明、且允许免消耗规则接管的能力成本。 */
        CUSTOM
    }

    public enum StaminaConsumptionResult {
        PASS,
        CONSUME,
        SKIP
    }

    public enum MoodPenaltyProfile {
        HIGH,
        MID,
        DEPRESSIVE
    }

    private record ConsumptionRuleEntry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull StaminaConsumptionRule rule
    ) {
    }
}
