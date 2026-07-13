package dev.doctor4t.wathe.api.instinct;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 本能透视的公开接入点。
 *
 * <p>这里把本能逻辑拆成两层：</p>
 * <p>1. availability：当前本地玩家“是否拥有本能透视资格”；</p>
 * <p>2. highlight：某个目标实体“应该显示什么颜色，或是否强制隐藏”。</p>
 *
 * <p>扩展职业请优先注册 handler，不要再 mixin 到 {@code WatheClient}。
 * priority 越大越先执行；同 priority 下，普通注册默认后注册者先执行。
 * Wathe 内置杀手本能也使用 priority {@link #DEFAULT_PRIORITY}，但会走默认注册入口，
 * 因此同为 0 的扩展逻辑仍可以自然覆盖它。</p>
 */
public final class InstinctApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<AvailabilityEntry> AVAILABILITY_COMPARATOR =
            Comparator.<AvailabilityEntry>comparingInt(AvailabilityEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(AvailabilityEntry::order).reversed());
    private static final Comparator<HighlightEntry> HIGHLIGHT_COMPARATOR =
            Comparator.<HighlightEntry>comparingInt(HighlightEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(HighlightEntry::order).reversed());

    private static final List<AvailabilityEntry> AVAILABILITY_HANDLERS = new ArrayList<>();
    private static final List<HighlightEntry> HIGHLIGHT_HANDLERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private InstinctApi() {
    }

    /**
     * 注册“是否开启本能”的判定。
     *
     * <p>返回 {@link AvailabilityResult#PASS} 表示不处理，继续把机会交给低优先级 handler；
     * 返回 ENABLE / DISABLE 会立即结束判定。Convener 这类全局压制应使用较高 priority
     * 返回 DISABLE，这样所有依赖 {@code WatheClient.isInstinctEnabled()} 的透视都会被统一关掉。</p>
     */
    public static void registerAvailability(@NotNull Identifier id, int priority, @NotNull AvailabilityHandler handler) {
        registerAvailability(id, priority, nextOrder++, handler);
    }

    /**
     * Wathe 本体专用的默认注册入口。
     *
     * <p>它仍然使用 priority 0，但同 priority 时会排在普通扩展注册之后。
     * 这样“默认杀手本能是 0”这个语义不变，同时 Jester 这类同为 0 的扩展职业
     * 不需要为了覆盖 Wathe fallback 而人为抬高优先级。</p>
     */
    public static void registerDefaultAvailability(@NotNull Identifier id, int priority, @NotNull AvailabilityHandler handler) {
        registerAvailability(id, priority, Long.MIN_VALUE, handler);
    }

    private static synchronized void registerAvailability(@NotNull Identifier id, int priority, long order, @NotNull AvailabilityHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        AVAILABILITY_HANDLERS.removeIf(entry -> entry.id().equals(id));
        AVAILABILITY_HANDLERS.add(new AvailabilityEntry(id, priority, order, handler));
        AVAILABILITY_HANDLERS.sort(AVAILABILITY_COMPARATOR);
    }

    /**
     * 注册“目标描边颜色”的判定。
     *
     * <p>返回 {@link HighlightResult#pass()} 表示继续向下询问；
     * 返回 {@link HighlightResult#color(int)} 表示使用该颜色并停止；
     * 返回 {@link HighlightResult#hide()} 表示强制不给该实体描边并停止。</p>
     */
    public static void registerHighlight(@NotNull Identifier id, int priority, @NotNull HighlightHandler handler) {
        registerHighlight(id, priority, nextOrder++, handler);
    }

    /**
     * Wathe 本体专用的默认高亮注册入口，排序规则同 {@link #registerDefaultAvailability}。
     */
    public static void registerDefaultHighlight(@NotNull Identifier id, int priority, @NotNull HighlightHandler handler) {
        registerHighlight(id, priority, Long.MIN_VALUE, handler);
    }

    private static synchronized void registerHighlight(@NotNull Identifier id, int priority, long order, @NotNull HighlightHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        HIGHLIGHT_HANDLERS.removeIf(entry -> entry.id().equals(id));
        HIGHLIGHT_HANDLERS.add(new HighlightEntry(id, priority, order, handler));
        HIGHLIGHT_HANDLERS.sort(HIGHLIGHT_COMPARATOR);
    }

    public static @NotNull AvailabilityResult resolveAvailability(@NotNull PlayerEntity viewer) {
        for (AvailabilityEntry entry : availabilitySnapshot()) {
            AvailabilityResult result = entry.handler().getAvailability(viewer);
            if (result != null && result != AvailabilityResult.PASS) {
                return result;
            }
        }
        return AvailabilityResult.PASS;
    }

    public static @NotNull HighlightResult resolveHighlight(@NotNull PlayerEntity viewer, @NotNull Entity target) {
        for (HighlightEntry entry : highlightSnapshot()) {
            HighlightResult result = entry.handler().getHighlight(viewer, target);
            if (result != null && result.action() != HighlightResult.Action.PASS) {
                return result;
            }
        }
        return HighlightResult.pass();
    }

    private static synchronized List<AvailabilityEntry> availabilitySnapshot() {
        return List.copyOf(AVAILABILITY_HANDLERS);
    }

    private static synchronized List<HighlightEntry> highlightSnapshot() {
        return List.copyOf(HIGHLIGHT_HANDLERS);
    }

    @FunctionalInterface
    public interface AvailabilityHandler {
        @NotNull AvailabilityResult getAvailability(@NotNull PlayerEntity viewer);
    }

    public enum AvailabilityResult {
        PASS,
        ENABLE,
        DISABLE
    }

    @FunctionalInterface
    public interface HighlightHandler {
        @NotNull HighlightResult getHighlight(@NotNull PlayerEntity viewer, @NotNull Entity target);
    }

    public record HighlightResult(@NotNull Action action, int color) {
        private static final HighlightResult PASS = new HighlightResult(Action.PASS, -1);
        private static final HighlightResult HIDE = new HighlightResult(Action.HIDE, -1);

        public static @NotNull HighlightResult pass() {
            return PASS;
        }

        public static @NotNull HighlightResult hide() {
            return HIDE;
        }

        public static @NotNull HighlightResult color(int color) {
            return new HighlightResult(Action.COLOR, color);
        }

        public enum Action {
            PASS,
            COLOR,
            HIDE
        }
    }

    private record AvailabilityEntry(@NotNull Identifier id, int priority, long order, @NotNull AvailabilityHandler handler) {
    }

    private record HighlightEntry(@NotNull Identifier id, int priority, long order, @NotNull HighlightHandler handler) {
    }
}
