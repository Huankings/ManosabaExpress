package dev.doctor4t.wathe.api.blackout;

import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.WorldBlackoutComponent;
import dev.doctor4t.wathe.game.GameConstants;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 停电机制公开 API。
 *
 * <p>扩展职业模组应该通过这里接入停电，而不是 mixin
 * {@link WorldBlackoutComponent} 的私有字段或客户端 HUD。这样停电结束、新局初始化、
 * 调试指令和客户端黑幕同步都能由 Wathe 本体统一收口。</p>
 */
public final class BlackoutApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<PrioritizedDurationModifier> DURATION_COMPARATOR =
            Comparator.<PrioritizedDurationModifier>comparingInt(PrioritizedDurationModifier::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedDurationModifier::order).reversed());
    private static final Comparator<PrioritizedEffectRule> EFFECT_COMPARATOR =
            Comparator.<PrioritizedEffectRule>comparingInt(PrioritizedEffectRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedEffectRule::order).reversed());

    private static final List<PrioritizedDurationModifier> DURATION_MODIFIERS = new ArrayList<>();
    private static final List<PrioritizedEffectRule> EFFECT_RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private BlackoutApi() {
    }

    /**
     * 判断指定世界当前是否处于 Wathe 停电期。
     */
    public static boolean isActive(@Nullable World world) {
        return world != null && WorldBlackoutComponent.KEY.get(world).isBlackoutActive();
    }

    /**
     * 触发一次停电。
     *
     * <p>服务端调试指令、商店物品和扩展职业都应走这个入口；
     * 具体灯光关闭、时间线同步、环境音和药水分配仍由 Wathe 组件统一完成。</p>
     */
    public static boolean trigger(@NotNull ServerWorld world) {
        return WorldBlackoutComponent.KEY.get(world).triggerBlackout();
    }

    /**
     * 立刻恢复供电并清理 Wathe 自己发放的停电药水效果。
     */
    public static void restore(@NotNull ServerWorld world) {
        WorldBlackoutComponent.KEY.get(world).restorePower();
    }

    /**
     * 兼容语义更直白的命名，方便扩展侧读代码时理解“工程师恢复电力”之类的调用。
     */
    public static void restorePower(@NotNull ServerWorld world) {
        restore(world);
    }

    /**
     * 注册停电持续时间修改器。
     *
     * <p>handler 会按 priority 从高到低依次拿到当前时长并返回修改后的时长。
     * 适合地图、游戏模式或特殊职业把“开始恢复 / 完全恢复”的时间统一拉长或缩短。</p>
     */
    public static void registerDurationModifier(@NotNull Identifier id, int priority, @NotNull DurationModifier modifier) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        synchronized (DURATION_MODIFIERS) {
            DURATION_MODIFIERS.removeIf(entry -> entry.id().equals(id));
            DURATION_MODIFIERS.add(new PrioritizedDurationModifier(id, priority, nextOrder++, modifier));
            DURATION_MODIFIERS.sort(DURATION_COMPARATOR);
        }
    }

    /**
     * 注册停电药水效果规则。
     *
     * <p>规则返回 PASS 表示继续给后续规则和 Wathe 默认阵营逻辑处理；
     * 返回 NIGHT_VISION / BLINDNESS / NONE 会立刻成为最终结果。
     * 同优先级下后注册者先执行，方便扩展在本体默认逻辑之外做窄范围覆盖。</p>
     */
    public static void registerEffectRule(@NotNull Identifier id, int priority, @NotNull EffectRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (EFFECT_RULES) {
            EFFECT_RULES.removeIf(entry -> entry.id().equals(id));
            EFFECT_RULES.add(new PrioritizedEffectRule(id, priority, nextOrder++, rule));
            EFFECT_RULES.sort(EFFECT_COMPARATOR);
        }
    }

    /**
     * 解析本轮停电的服务端持续时间。
     */
    public static @NotNull BlackoutDuration resolveDuration(@NotNull ServerWorld world, @NotNull GameWorldComponent gameWorld) {
        BlackoutDurationContext context = new BlackoutDurationContext(world, gameWorld);
        BlackoutDuration duration = BlackoutDuration.of(GameConstants.BLACKOUT_MIN_DURATION, GameConstants.BLACKOUT_MAX_DURATION);
        for (PrioritizedDurationModifier entry : durationModifierSnapshot()) {
            BlackoutDuration modified = entry.modifier().modifyDuration(context, duration);
            if (modified != null) {
                duration = modified;
            }
        }
        return duration;
    }

    /**
     * 解析玩家在停电期间应获得的药水效果。
     */
    public static @NotNull BlackoutEffectResult resolveEffect(@NotNull BlackoutEffectContext context) {
        for (PrioritizedEffectRule entry : effectRuleSnapshot()) {
            BlackoutEffectResult result = entry.rule().resolve(context);
            if (result != null && result.action() != BlackoutEffectResult.Action.PASS) {
                return result;
            }
        }

        /*
         * Wathe 默认分配：
         * 1. 杀手阵营获得夜视，方便停电期间继续执行杀手侧玩法；
         * 2. 平民、义警、中立默认获得失明，等待扩展按更细职业分组覆盖。
         */
        Role role = context.role();
        if (role != null && role.getFaction() == Faction.KILLER) {
            return BlackoutEffectResult.nightVision();
        }
        if (role != null && (role.getFaction() == Faction.CIVILIAN
                || role.getFaction() == Faction.VIGILANTE
                || role.getFaction() == Faction.NEUTRAL)) {
            return BlackoutEffectResult.blindness();
        }
        return BlackoutEffectResult.none();
    }

    private static List<PrioritizedDurationModifier> durationModifierSnapshot() {
        synchronized (DURATION_MODIFIERS) {
            return List.copyOf(DURATION_MODIFIERS);
        }
    }

    private static List<PrioritizedEffectRule> effectRuleSnapshot() {
        synchronized (EFFECT_RULES) {
            return List.copyOf(EFFECT_RULES);
        }
    }

    @FunctionalInterface
    public interface DurationModifier {
        @Nullable BlackoutDuration modifyDuration(@NotNull BlackoutDurationContext context, @NotNull BlackoutDuration current);
    }

    @FunctionalInterface
    public interface EffectRule {
        @NotNull BlackoutEffectResult resolve(@NotNull BlackoutEffectContext context);
    }

    private record PrioritizedDurationModifier(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull DurationModifier modifier
    ) {
    }

    private record PrioritizedEffectRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull EffectRule rule
    ) {
    }
}
