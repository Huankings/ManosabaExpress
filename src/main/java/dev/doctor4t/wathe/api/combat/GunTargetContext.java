package dev.doctor4t.wathe.api.combat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端枪械射线目标上下文。
 *
 * @param user          本地开火玩家
 * @param stack         当前手持枪械
 * @param range         Wathe 或物品给出的默认射线距离
 * @param defaultTarget 默认射线命中的目标；没有命中时为 {@code null}
 */
public record GunTargetContext(@NotNull PlayerEntity user,
                               @NotNull ItemStack stack,
                               double range,
                               @Nullable HitResult defaultTarget) {
}
