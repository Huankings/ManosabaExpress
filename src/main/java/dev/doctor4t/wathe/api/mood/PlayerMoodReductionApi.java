package dev.doctor4t.wathe.api.mood;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家真实心情的扣减规则。自然衰减和普通改值共用此入口，恢复心情与组件快照读取继续走各自的流程。
 */
public final class PlayerMoodReductionApi {
    /** 普通职业或词条保护规则的默认优先级。 */
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<RuleEntry> RULE_ORDER = Comparator.comparingInt(RuleEntry::priority).reversed();
    // 通用初始化发布只读规则，两端每 tick 读取时无需竞争注册锁。
    private static volatile List<RuleEntry> rules = List.of();

    private PlayerMoodReductionApi() {
    }

    /**
     * 注册扣减规则。优先级从高到低，同优先级后注册者先执行，相同 ID 会替换旧规则。
     * 第一条返回 APPLY 或 SKIP 的规则决定结果，全部 PASS 时按原样扣减。
     *
     * <p>自然衰减会在服务端结算和客户端预测中调用规则。请使用两端已同步的职业或组件状态，
     * 直接读取上下文中的心情值。回调只作判断，不应写入组件、同步发包或再次调用扣减解析。</p>
     */
    public static synchronized void registerRule(@NotNull Identifier id, int priority, @NotNull MoodReductionRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        List<RuleEntry> updated = new ArrayList<>(rules);
        updated.removeIf(entry -> entry.id().equals(id));
        // 稳定排序使新规则在同一优先级组里排在已有规则前面。
        updated.addFirst(new RuleEntry(id, priority, rule));
        updated.sort(RULE_ORDER);
        rules = List.copyOf(updated);
    }

    /**
     * 解析真实心情的目标值。组件会先把目标值钳制到合法区间。
     * 指令强制改值与已经判定成立的枪械误伤惩罚直接通过，无需参与保护规则的优先级竞争。
     */
    public static float resolveMood(@NotNull PlayerEntity player, float currentMood, float requestedMood,
                                   @NotNull MoodReductionCause cause) {
        Objects.requireNonNull(cause, "cause");
        List<RuleEntry> currentRules = rules;
        if (cause == MoodReductionCause.ADMIN_COMMAND || cause == MoodReductionCause.GUN_FRIENDLY_FIRE_PENALTY
                || !(requestedMood < currentMood) || currentRules.isEmpty()) {
            return requestedMood;
        }

        MoodReductionContext context = new MoodReductionContext(player, currentMood, requestedMood, cause);
        for (RuleEntry entry : currentRules) {
            MoodReductionResult result = entry.rule().resolve(context);
            if (result == MoodReductionResult.SKIP) {
                return currentMood;
            }
            if (result == MoodReductionResult.APPLY) {
                return requestedMood;
            }
        }
        return requestedMood;
    }

    public enum MoodReductionCause {
        /** 身上有未完成任务时的自然衰减，两端使用同一来源。 */
        TASK_DRAIN,
        /** 普通 setMood 调用，例如职业技能直接扣减。 */
        DIRECT,
        /** 已通过误伤规则和反火判定，由枪械结算实际施加的心情惩罚。 */
        GUN_FRIENDLY_FIRE_PENALTY,
        /** 服务端管理员调试指令的强制写入。 */
        ADMIN_COMMAND
    }

    public enum MoodReductionResult {
        PASS,
        APPLY,
        SKIP
    }

    /** 扣减前的实际值、钳制后的目标值，以及本次改值的来源。 */
    public record MoodReductionContext(PlayerEntity player, float currentMood, float requestedMood,
                                       MoodReductionCause cause) {
    }

    @FunctionalInterface
    public interface MoodReductionRule {
        @NotNull MoodReductionResult resolve(@NotNull MoodReductionContext context);
    }

    private record RuleEntry(Identifier id, int priority, MoodReductionRule rule) {
    }
}
