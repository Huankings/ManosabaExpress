package dev.doctor4t.wathe.api.combat;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 默认匕首刺杀后的冷却修正公开 API。
 *
 * <p>这里对齐 {@link GunShotApi} 的枪械冷却流水线：所有 modifier 都会执行，
 * 后一个 modifier 能读到前一个已经修正的 {@code currentCooldown}。这适合表达
 * “某职业无冷却”、“某个词条把冷却缩短到 1 秒”等可组合规则，
 * 扩展不再需要 mixin {@code KnifeStabPayload.Receiver}。</p>
 *
 * <p>priority 越大越先执行；同 priority 下后注册的 modifier 先执行。
 * 注册相同 id 会替换旧规则，避免开发环境重复初始化时叠加。</p>
 */
public final class KnifeCooldownApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());
    private static final List<Entry> MODIFIERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private KnifeCooldownApi() {
    }

    /**
     * 注册一个匕首冷却修正器。
     *
     * <p>modifier 应基于传入的 {@code currentCooldown} 返回新值。
     * 仅想缩短冷却时推荐使用 {@code Math.min(currentCooldown, targetTicks)}，
     * 这样不会把其它高优先级规则已经缩短的冷却重新拉长。</p>
     */
    public static void registerCooldownModifier(
            @NotNull Identifier id,
            int priority,
            @NotNull Modifier modifier
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        synchronized (MODIFIERS) {
            MODIFIERS.removeIf(entry -> entry.id().equals(id));
            MODIFIERS.add(new Entry(id, priority, nextOrder++, modifier));
            MODIFIERS.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 计算本次刺杀的最终冷却，并把每一步结果限制为非负数。
     *
     * <p>即使 {@link KnifeCooldownContext#defaultCooldownApplies()} 为 false，Wathe 仍会调用本方法。
     * 这是为了让依赖“本次死亡尝试已完成”的扩展消费临时标记；
     * 是否把结果真正写入 {@code ItemCooldownManager} 仍由 Wathe 默认模式规则决定。</p>
     */
    public static int modifyCooldown(@NotNull KnifeCooldownContext context) {
        int currentCooldown = Math.max(0, context.baseCooldown());
        for (Entry entry : snapshot()) {
            currentCooldown = Math.max(0, entry.modifier().modify(context, currentCooldown));
        }
        return currentCooldown;
    }

    private static List<Entry> snapshot() {
        synchronized (MODIFIERS) {
            return List.copyOf(MODIFIERS);
        }
    }

    @FunctionalInterface
    public interface Modifier {
        int modify(@NotNull KnifeCooldownContext context, int currentCooldown);
    }

    private record Entry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull Modifier modifier
    ) {
    }
}
