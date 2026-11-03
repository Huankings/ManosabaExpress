package dev.doctor4t.wathe.api.combat;

import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 客户端匕首目标规则的返回结果。 */
public record KnifeTargetResult(@NotNull Action action, @Nullable HitResult target) {
    private static final KnifeTargetResult PASS = new KnifeTargetResult(Action.PASS, null);
    private static final KnifeTargetResult MISS = new KnifeTargetResult(Action.MISS, null);

    public static @NotNull KnifeTargetResult pass() {
        return PASS;
    }

    public static @NotNull KnifeTargetResult miss() {
        return MISS;
    }

    public static @NotNull KnifeTargetResult target(@NotNull HitResult target) {
        return new KnifeTargetResult(Action.TARGET, target);
    }

    public enum Action {
        /** 当前规则不处理，继续询问低优先级规则或使用 Wathe 默认目标。 */
        PASS,
        /** 使用扩展提供的目标。 */
        TARGET,
        /** 强制视为未命中。 */
        MISS
    }
}
