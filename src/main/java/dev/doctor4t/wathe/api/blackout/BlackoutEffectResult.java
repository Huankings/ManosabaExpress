package dev.doctor4t.wathe.api.blackout;

import org.jetbrains.annotations.NotNull;

/**
 * 停电药水效果解析结果。
 *
 * <p>PASS 表示当前 handler 不处理，继续交给后续规则和默认阵营逻辑；
 * NONE 表示明确不给任何停电药水，并清掉 Wathe 自己持有的旧效果。</p>
 */
public record BlackoutEffectResult(@NotNull Action action) {
    private static final BlackoutEffectResult PASS = new BlackoutEffectResult(Action.PASS);
    private static final BlackoutEffectResult NONE = new BlackoutEffectResult(Action.NONE);
    private static final BlackoutEffectResult NIGHT_VISION = new BlackoutEffectResult(Action.NIGHT_VISION);
    private static final BlackoutEffectResult BLINDNESS = new BlackoutEffectResult(Action.BLINDNESS);

    public enum Action {
        PASS,
        NONE,
        NIGHT_VISION,
        BLINDNESS
    }

    public static @NotNull BlackoutEffectResult pass() {
        return PASS;
    }

    public static @NotNull BlackoutEffectResult none() {
        return NONE;
    }

    public static @NotNull BlackoutEffectResult nightVision() {
        return NIGHT_VISION;
    }

    public static @NotNull BlackoutEffectResult blindness() {
        return BLINDNESS;
    }
}
