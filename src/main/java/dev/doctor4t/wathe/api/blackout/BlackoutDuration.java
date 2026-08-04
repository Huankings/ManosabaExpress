package dev.doctor4t.wathe.api.blackout;

import org.jetbrains.annotations.NotNull;

/**
 * 一次停电的全局时间线配置。
 *
 * <p>minTicks 表示“电力开始恢复”的时间点，maxTicks 表示“电力完全恢复”的时间点。
 * 两个值都按“从停电触发开始经过了多少 tick”计算，而不是某一盏灯的随机恢复时间。</p>
 */
public record BlackoutDuration(int minTicks, int maxTicks) {
    public BlackoutDuration {
        minTicks = Math.max(1, minTicks);
        maxTicks = Math.max(minTicks + 1, maxTicks);
    }

    public static @NotNull BlackoutDuration of(int minTicks, int maxTicks) {
        return new BlackoutDuration(minTicks, maxTicks);
    }
}
