package dev.doctor4t.wathe.api.combat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端匕首射线目标覆写上下文。
 *
 * @param user          本地使用匕首或复用匕首射线的玩家
 * @param stack         本次选择目标时使用的物品栈
 * @param range         物品给出的最大射线距离
 * @param purpose       本次查询用于准心显示还是用于真实攻击发包
 * @param defaultTarget Wathe 默认玩家射线结果；未命中时为 {@code null}
 */
public record KnifeTargetContext(
        @NotNull PlayerEntity user,
        @NotNull ItemStack stack,
        double range,
        @NotNull Purpose purpose,
        @Nullable HitResult defaultTarget
) {
    public enum Purpose {
        /** 只用于准心、HUD 和名字提示，必须尊重 TARGET 可见性。 */
        VISIBLE,
        /** 用于真实 C2S 攻击发包，必须使用 ATTACK 可攻击性。 */
        ATTACK
    }
}
