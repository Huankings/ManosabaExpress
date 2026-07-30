package dev.doctor4t.wathe.api.combat;

import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 枪械开火与左轮惩罚公开入口。
 *
 * <p>所有列表都按 priority 从高到低执行；同 priority 下，后注册的处理器先执行。
 * 返回 PASS 的处理器不会终止链路，会继续交给低优先级处理器或 Wathe 默认逻辑。</p>
 */
public final class GunShotApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<PrioritizedShotHandler> SHOT_HANDLER_COMPARATOR =
            Comparator.<PrioritizedShotHandler>comparingInt(PrioritizedShotHandler::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedShotHandler::order).reversed());
    private static final Comparator<PrioritizedPenaltyRule> PENALTY_RULE_COMPARATOR =
            Comparator.<PrioritizedPenaltyRule>comparingInt(PrioritizedPenaltyRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedPenaltyRule::order).reversed());
    private static final Comparator<PrioritizedCooldownModifier> COOLDOWN_MODIFIER_COMPARATOR =
            Comparator.<PrioritizedCooldownModifier>comparingInt(PrioritizedCooldownModifier::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedCooldownModifier::order).reversed());
    private static final Comparator<PrioritizedTargetRule> TARGET_RULE_COMPARATOR =
            Comparator.<PrioritizedTargetRule>comparingInt(PrioritizedTargetRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedTargetRule::order).reversed());

    private static final List<PrioritizedShotHandler> SHOT_HANDLERS = new ArrayList<>();
    private static final List<PrioritizedPenaltyRule> PENALTY_RULES = new ArrayList<>();
    private static final List<PrioritizedCooldownModifier> COOLDOWN_MODIFIERS = new ArrayList<>();
    private static final List<PrioritizedTargetRule> TARGET_RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private GunShotApi() {
    }

    /**
     * 注册服务端枪击接管处理器。
     *
     * <p>Wathe 收到 {@link dev.doctor4t.wathe.util.GunShootPayload} 后会先执行这里的处理器，
     * 然后才判断手中物品是否属于 Wathe 默认枪械标签。这样扩展枪械即使没有加入
     * {@code wathe:guns} 标签，也能复用同一条客户端开火包并自行完成结算。</p>
     *
     * <p>典型用途：强盗手枪、赏金手枪、无声左轮这类“仍像枪一样开火，
     * 但命中后掉枪、冷却、声音或奖励规则不同”的物品。</p>
     */
    public static void registerShotHandler(@NotNull Identifier id, int priority, @NotNull GunShotHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (SHOT_HANDLERS) {
            SHOT_HANDLERS.removeIf(entry -> entry.id().equals(id));
            SHOT_HANDLERS.add(new PrioritizedShotHandler(id, priority, nextOrder++, handler));
            SHOT_HANDLERS.sort(SHOT_HANDLER_COMPARATOR);
        }
    }

    /**
     * 依优先级执行服务端枪击接管链。
     *
     * <p>返回 {@link GunShotResult#PASS} 时 Wathe 会继续执行低优先级 handler 或默认枪击逻辑；
     * 返回 {@link GunShotResult#CANCEL} / {@link GunShotResult#HANDLED} 时，本次开火请求到此结束。</p>
     */
    public static @NotNull GunShotResult handleShot(@NotNull GunShotContext context) {
        for (PrioritizedShotHandler entry : shotHandlerSnapshot()) {
            GunShotResult result = entry.handler().handle(context);
            if (result != null && result != GunShotResult.PASS) {
                return result;
            }
        }
        return GunShotResult.PASS;
    }

    /**
     * 注册 Wathe 默认左轮“误伤无辜者惩罚”的判定规则。
     *
     * <p>这个惩罚包含两部分：平民开枪误杀时可能反火自杀，以及非反火时延迟掉落左轮并清空心情。
     * 扩展如果希望某个目标“本次不按无辜者处理”，应返回 {@link RevolverPenaltyResult#SKIP}；
     * 如果希望强制按无辜者处理，返回 {@link RevolverPenaltyResult#APPLY}。</p>
     */
    public static void registerInnocentRevolverPenaltyRule(@NotNull Identifier id,
                                                           int priority,
                                                           @NotNull RevolverPenaltyRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (PENALTY_RULES) {
            PENALTY_RULES.removeIf(entry -> entry.id().equals(id));
            PENALTY_RULES.add(new PrioritizedPenaltyRule(id, priority, nextOrder++, rule));
            PENALTY_RULES.sort(PENALTY_RULE_COMPARATOR);
        }
    }

    /**
     * 解析左轮误伤惩罚的最终结果。
     *
     * <p>所有扩展都 PASS 时，回落到 Wathe 原始的 {@code game.isInnocent(target)}。
     * 这保证没有扩展接入时，默认左轮行为完全不变。</p>
     */
    public static @NotNull RevolverPenaltyResult resolveInnocentRevolverPenalty(@NotNull RevolverPenaltyContext context) {
        for (PrioritizedPenaltyRule entry : penaltyRuleSnapshot()) {
            RevolverPenaltyResult result = entry.rule().resolve(context);
            if (result != null && result != RevolverPenaltyResult.PASS) {
                return result;
            }
        }
        return context.targetNormallyInnocent() ? RevolverPenaltyResult.APPLY : RevolverPenaltyResult.SKIP;
    }

    public static boolean shouldApplyInnocentRevolverPenalty(@NotNull RevolverPenaltyContext context) {
        return resolveInnocentRevolverPenalty(context) == RevolverPenaltyResult.APPLY;
    }

    /**
     * 注册枪械冷却修正器。
     *
     * <p>冷却修正器不是短路链，而是“流水线”：每个 handler 都会收到前一个 handler 算出的
     * {@code currentCooldown}。适合胆小鬼、镇静剂、阵营冷却修正这类需要叠乘或叠加的机制。</p>
     */
    public static void registerCooldownModifier(@NotNull Identifier id,
                                                int priority,
                                                @NotNull GunCooldownModifier modifier) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        synchronized (COOLDOWN_MODIFIERS) {
            COOLDOWN_MODIFIERS.removeIf(entry -> entry.id().equals(id));
            COOLDOWN_MODIFIERS.add(new PrioritizedCooldownModifier(id, priority, nextOrder++, modifier));
            COOLDOWN_MODIFIERS.sort(COOLDOWN_MODIFIER_COMPARATOR);
        }
    }

    /**
     * 按优先级依次修正冷却，并确保结果不会小于 0。
     */
    public static int modifyCooldown(@NotNull GunCooldownContext context) {
        int currentCooldown = Math.max(0, context.baseCooldown());
        for (PrioritizedCooldownModifier entry : cooldownModifierSnapshot()) {
            currentCooldown = Math.max(0, entry.modifier().modify(context, currentCooldown));
        }
        return currentCooldown;
    }

    /**
     * 注册客户端枪械射线目标覆写规则。
     *
     * <p>该钩子运行在物品 use 的客户端射线阶段，只决定本次发送给服务端的实体 id。
     * 服务端仍会在 {@code GunShootPayload} 中重新校验实体、距离、存活状态和具体开火规则。</p>
     */
    public static void registerTargetRule(@NotNull Identifier id, int priority, @NotNull GunTargetRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (TARGET_RULES) {
            TARGET_RULES.removeIf(entry -> entry.id().equals(id));
            TARGET_RULES.add(new PrioritizedTargetRule(id, priority, nextOrder++, rule));
            TARGET_RULES.sort(TARGET_RULE_COMPARATOR);
        }
    }

    /**
     * 解析客户端最终射线目标。
     *
     * <p>{@link GunTargetResult.Action#MISS} 会强制视为未命中，用于假左轮这类“能开火但不应选中玩家”的物品；
     * {@link GunTargetResult.Action#TARGET} 可以替换默认目标，用于特殊瞄准来源或扩展可见目标。</p>
     */
    public static @Nullable HitResult resolveTarget(@NotNull GunTargetContext context) {
        for (PrioritizedTargetRule entry : targetRuleSnapshot()) {
            GunTargetResult result = entry.rule().resolve(context);
            if (result == null || result.action() == GunTargetResult.Action.PASS) {
                continue;
            }
            return result.action() == GunTargetResult.Action.MISS ? null : result.target();
        }
        return context.defaultTarget();
    }

    private static List<PrioritizedShotHandler> shotHandlerSnapshot() {
        synchronized (SHOT_HANDLERS) {
            return List.copyOf(SHOT_HANDLERS);
        }
    }

    private static List<PrioritizedPenaltyRule> penaltyRuleSnapshot() {
        synchronized (PENALTY_RULES) {
            return List.copyOf(PENALTY_RULES);
        }
    }

    private static List<PrioritizedCooldownModifier> cooldownModifierSnapshot() {
        synchronized (COOLDOWN_MODIFIERS) {
            return List.copyOf(COOLDOWN_MODIFIERS);
        }
    }

    private static List<PrioritizedTargetRule> targetRuleSnapshot() {
        synchronized (TARGET_RULES) {
            return List.copyOf(TARGET_RULES);
        }
    }

    @FunctionalInterface
    public interface GunShotHandler {
        @NotNull GunShotResult handle(@NotNull GunShotContext context);
    }

    @FunctionalInterface
    public interface RevolverPenaltyRule {
        @NotNull RevolverPenaltyResult resolve(@NotNull RevolverPenaltyContext context);
    }

    @FunctionalInterface
    public interface GunCooldownModifier {
        int modify(@NotNull GunCooldownContext context, int currentCooldown);
    }

    @FunctionalInterface
    public interface GunTargetRule {
        @NotNull GunTargetResult resolve(@NotNull GunTargetContext context);
    }

    private record PrioritizedShotHandler(@NotNull Identifier id,
                                          int priority,
                                          long order,
                                          @NotNull GunShotHandler handler) {
    }

    private record PrioritizedPenaltyRule(@NotNull Identifier id,
                                          int priority,
                                          long order,
                                          @NotNull RevolverPenaltyRule rule) {
    }

    private record PrioritizedCooldownModifier(@NotNull Identifier id,
                                               int priority,
                                               long order,
                                               @NotNull GunCooldownModifier modifier) {
    }

    private record PrioritizedTargetRule(@NotNull Identifier id,
                                         int priority,
                                         long order,
                                         @NotNull GunTargetRule rule) {
    }
}
