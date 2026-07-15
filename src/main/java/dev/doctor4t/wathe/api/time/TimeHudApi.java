package dev.doctor4t.wathe.api.time;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 顶部时间 HUD 的公开接入点。
 *
 * <p>扩展职业或词条如果想把自己的倒计时显示到屏幕正上方，
 * 请注册 provider，而不是继续 mixin 到 {@code TimeRenderer}。
 * provider 按 priority 从高到低依次询问；返回 PASS 表示“不处理，交给下一个”，
 * 返回 SHOW/HIDE 会立即结束判定。</p>
 *
 * <p>这个 API 只暴露通用的 {@link PlayerEntity}，不暴露客户端 HUD 类。
 * 这样扩展侧只需要描述“现在要不要显示一个时间、显示多少 tick、用什么颜色策略”，
 * 真正的滚动数字动画和屏幕坐标仍由 Wathe 的 TimeRenderer 统一负责。</p>
 */
public final class TimeHudApi {
    public static final int DEFAULT_PRIORITY = 0;

    /**
     * 传给 {@link TimeDisplay#showDynamic(int, int, int)} 的低时间警告阈值。
     * 使用这个值时，TimeRenderer 不会因为时间数值很低而强制染成红色，
     * 适合“自然增长计时器”或不表达危险倒计时的特殊时间。
     */
    public static final int NO_LOW_TIME_WARNING = -1;

    /**
     * Wathe 原本的跳变阈值：目标时间和当前滚动目标差距超过 10 tick 时，
     * 才触发一次明显的红/绿变色反馈。
     */
    public static final int DEFAULT_CHANGE_FLASH_THRESHOLD = 10;

    private static final Comparator<ProviderEntry> PROVIDER_COMPARATOR =
            Comparator.<ProviderEntry>comparingInt(ProviderEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ProviderEntry::order).reversed());

    private static final List<ProviderEntry> PROVIDERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private TimeHudApi() {
    }

    /**
     * 注册一个普通扩展 provider。
     *
     * <p>同一个 id 重复注册时会替换旧 provider。priority 越大越早执行；
     * 同 priority 下，后注册的普通扩展会排在前面。</p>
     */
    public static void registerProvider(@NotNull Identifier id, int priority, @NotNull TimeDisplayProvider provider) {
        registerProvider(id, priority, nextOrder++, provider);
    }

    /**
     * Wathe 本体默认逻辑专用入口。
     *
     * <p>默认 provider 仍然使用 priority 0，但同 priority 时会排在普通扩展之后。
     * 这样扩展模组只要正常注册 priority 0 或更高，就可以自然覆盖 Wathe 默认回合时间。</p>
     */
    public static void registerDefaultProvider(@NotNull Identifier id, int priority, @NotNull TimeDisplayProvider provider) {
        registerProvider(id, priority, Long.MIN_VALUE, provider);
    }

    private static synchronized void registerProvider(@NotNull Identifier id, int priority, long order, @NotNull TimeDisplayProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new ProviderEntry(id, priority, order, provider));
        PROVIDERS.sort(PROVIDER_COMPARATOR);
    }

    /**
     * 解析当前玩家应该看到的顶部时间 HUD。
     *
     * <p>TimeRenderer 每帧调用这里。返回值会附带命中的 provider id，
     * TimeRenderer 用它判断“时间来源是否发生变化”，从而在切换到双活倒计时等特殊 HUD 时
     * 自动重置滚动数字状态，避免旧时间残留。</p>
     */
    public static @NotNull TimeDisplay resolveDisplay(@NotNull PlayerEntity viewer) {
        for (ProviderEntry entry : providerSnapshot()) {
            TimeDisplay display = entry.provider().getTimeDisplay(viewer);
            if (display != null && display.action() != TimeDisplay.Action.PASS) {
                return display.withSourceId(entry.id());
            }
        }
        return TimeDisplay.pass();
    }

    private static synchronized List<ProviderEntry> providerSnapshot() {
        return List.copyOf(PROVIDERS);
    }

    @FunctionalInterface
    public interface TimeDisplayProvider {
        @NotNull TimeDisplay getTimeDisplay(@NotNull PlayerEntity viewer);
    }

    /**
     * provider 对顶部时间 HUD 的一次显示决策。
     *
     * @param action               PASS/HIDE/SHOW 三态决策
     * @param ticks                SHOW 时要显示的 tick 数
     * @param colorMode            动态变色或固定颜色
     * @param fixedColor           固定颜色，alpha 会由 TimeRenderer 兜底补齐
     * @param lowTimeWarningTicks  小于该 tick 数时强制红色警告；负数表示关闭
     * @param changeFlashThreshold 时间跳变超过该阈值时触发增减时间变色；0 表示任意变化都触发
     * @param sourceId             由 API 解析时自动写入，扩展 provider 不需要自己填写
     */
    public record TimeDisplay(
            @NotNull Action action,
            int ticks,
            @NotNull ColorMode colorMode,
            int fixedColor,
            int lowTimeWarningTicks,
            int changeFlashThreshold,
            @Nullable Identifier sourceId
    ) {
        private static final TimeDisplay PASS = new TimeDisplay(
                Action.PASS,
                0,
                ColorMode.DYNAMIC,
                0xFFFFFFFF,
                NO_LOW_TIME_WARNING,
                DEFAULT_CHANGE_FLASH_THRESHOLD,
                null
        );
        private static final TimeDisplay HIDE = new TimeDisplay(
                Action.HIDE,
                0,
                ColorMode.DYNAMIC,
                0xFFFFFFFF,
                NO_LOW_TIME_WARNING,
                DEFAULT_CHANGE_FLASH_THRESHOLD,
                null
        );

        public static @NotNull TimeDisplay pass() {
            return PASS;
        }

        public static @NotNull TimeDisplay hide() {
            return HIDE;
        }

        /**
         * 显示一个使用 Wathe 默认动态颜色规则的时间。
         */
        public static @NotNull TimeDisplay show(int ticks) {
            return showDynamic(ticks, NO_LOW_TIME_WARNING, DEFAULT_CHANGE_FLASH_THRESHOLD);
        }

        /**
         * 显示一个使用 Wathe 默认动态颜色规则，并带低时间警告的倒计时。
         */
        public static @NotNull TimeDisplay showCountdown(int ticks, int lowTimeWarningTicks) {
            return showDynamic(ticks, lowTimeWarningTicks, DEFAULT_CHANGE_FLASH_THRESHOLD);
        }

        /**
         * 显示一个动态颜色时间。
         *
         * <p>动态颜色会根据时间目标变大/变小显示绿色或红色反馈。
         * 对自然增长计时器，可以传 {@link TimeHudApi#NO_LOW_TIME_WARNING} 关闭低时间强制红色。</p>
         */
        public static @NotNull TimeDisplay showDynamic(int ticks, int lowTimeWarningTicks, int changeFlashThreshold) {
            return new TimeDisplay(
                    Action.SHOW,
                    Math.max(0, ticks),
                    ColorMode.DYNAMIC,
                    0xFFFFFFFF,
                    lowTimeWarningTicks,
                    Math.max(0, changeFlashThreshold),
                    null
            );
        }

        /**
         * 显示一个固定颜色时间。
         *
         * <p>双重人格双活倒计时这类“特殊来源时间”适合用固定颜色，
         * 玩家可以一眼分辨它不是普通回合时间。</p>
         */
        public static @NotNull TimeDisplay showFixedColor(int ticks, int color) {
            return new TimeDisplay(
                    Action.SHOW,
                    Math.max(0, ticks),
                    ColorMode.FIXED,
                    color,
                    NO_LOW_TIME_WARNING,
                    DEFAULT_CHANGE_FLASH_THRESHOLD,
                    null
            );
        }

        private @NotNull TimeDisplay withSourceId(@NotNull Identifier sourceId) {
            return new TimeDisplay(
                    this.action,
                    this.ticks,
                    this.colorMode,
                    this.fixedColor,
                    this.lowTimeWarningTicks,
                    this.changeFlashThreshold,
                    sourceId
            );
        }

        public enum Action {
            PASS,
            HIDE,
            SHOW
        }

        public enum ColorMode {
            DYNAMIC,
            FIXED
        }
    }

    private record ProviderEntry(@NotNull Identifier id, int priority, long order, @NotNull TimeDisplayProvider provider) {
    }
}
