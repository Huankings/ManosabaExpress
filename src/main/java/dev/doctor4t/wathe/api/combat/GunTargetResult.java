package dev.doctor4t.wathe.api.combat;

import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端枪械射线目标的公开覆写结果。
 *
 * <p>服务端仍会在 {@code GunShootPayload} 里重新校验目标、距离和存活状态；
 * 这里仅决定客户端本次开火包里携带哪个实体 id。</p>
 */
public record GunTargetResult(@NotNull Action action, @Nullable HitResult target) {
    private static final GunTargetResult PASS = new GunTargetResult(Action.PASS, null);
    private static final GunTargetResult MISS = new GunTargetResult(Action.MISS, null);

    public static @NotNull GunTargetResult pass() {
        return PASS;
    }

    public static @NotNull GunTargetResult miss() {
        return MISS;
    }

    public static @NotNull GunTargetResult target(@NotNull HitResult target) {
        return new GunTargetResult(Action.TARGET, target);
    }

    public enum Action {
        /**
         * 当前规则不关心本次瞄准，继续交给低优先级规则或默认射线结果。
         */
        PASS,
        /**
         * 使用扩展提供的命中结果替换默认射线。
         */
        TARGET,
        /**
         * 强制视为未命中，客户端会向服务端发送 -1。
         */
        MISS
    }
}
