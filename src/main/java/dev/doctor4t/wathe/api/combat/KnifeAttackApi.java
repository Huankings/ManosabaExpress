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
 * 匕首客户端目标与服务端刺击公开 API。
 *
 * <p>规则按 priority 从高到低执行，同 priority 下后注册者先执行；相同 id 会替换旧注册。
 * 客户端规则只决定发给服务端的实体 id，服务端仍会执行独立的权威校验。</p>
 */
public final class KnifeAttackApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<TargetEntry> TARGET_COMPARATOR =
            Comparator.<TargetEntry>comparingInt(TargetEntry::priority).reversed()
                    .thenComparing(Comparator.comparingLong(TargetEntry::order).reversed());
    private static final Comparator<StabEntry> STAB_COMPARATOR =
            Comparator.<StabEntry>comparingInt(StabEntry::priority).reversed()
                    .thenComparing(Comparator.comparingLong(StabEntry::order).reversed());
    private static final List<TargetEntry> TARGET_RULES = new ArrayList<>();
    private static final List<StabEntry> STAB_HANDLERS = new ArrayList<>();
    private static long nextOrder;

    private KnifeAttackApi() {
    }

    public static void registerTargetRule(@NotNull Identifier id, int priority, @NotNull TargetRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (TARGET_RULES) {
            TARGET_RULES.removeIf(entry -> entry.id().equals(id));
            TARGET_RULES.add(new TargetEntry(id, priority, nextOrder++, rule));
            TARGET_RULES.sort(TARGET_COMPARATOR);
        }
    }

    public static @Nullable HitResult resolveTarget(@NotNull KnifeTargetContext context) {
        for (TargetEntry entry : targetSnapshot()) {
            KnifeTargetResult result = entry.rule().resolve(context);
            if (result == null || result.action() == KnifeTargetResult.Action.PASS) {
                continue;
            }
            return result.action() == KnifeTargetResult.Action.MISS ? null : result.target();
        }
        return context.defaultTarget();
    }

    public static void registerStabHandler(@NotNull Identifier id, int priority, @NotNull StabHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (STAB_HANDLERS) {
            STAB_HANDLERS.removeIf(entry -> entry.id().equals(id));
            STAB_HANDLERS.add(new StabEntry(id, priority, nextOrder++, handler));
            STAB_HANDLERS.sort(STAB_COMPARATOR);
        }
    }

    public static @NotNull KnifeStabResult handleStab(@NotNull KnifeStabContext context) {
        for (StabEntry entry : stabSnapshot()) {
            KnifeStabResult result = entry.handler().handle(context);
            if (result != null && result != KnifeStabResult.PASS) {
                return result;
            }
        }
        return KnifeStabResult.PASS;
    }

    private static List<TargetEntry> targetSnapshot() {
        synchronized (TARGET_RULES) {
            return List.copyOf(TARGET_RULES);
        }
    }

    private static List<StabEntry> stabSnapshot() {
        synchronized (STAB_HANDLERS) {
            return List.copyOf(STAB_HANDLERS);
        }
    }

    @FunctionalInterface
    public interface TargetRule {
        @NotNull KnifeTargetResult resolve(@NotNull KnifeTargetContext context);
    }

    @FunctionalInterface
    public interface StabHandler {
        @NotNull KnifeStabResult handle(@NotNull KnifeStabContext context);
    }

    private record TargetEntry(@NotNull Identifier id, int priority, long order, @NotNull TargetRule rule) {
    }

    private record StabEntry(@NotNull Identifier id, int priority, long order, @NotNull StabHandler handler) {
    }
}
