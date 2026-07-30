package dev.doctor4t.wathe.api.combat;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 枪械冷却修正上下文。
 *
 * @param shooter      开火玩家
 * @param stack        开火时手持的枪械
 * @param target       本次服务端确认命中的玩家；未命中或未确认时为 {@code null}
 * @param baseCooldown Wathe 或扩展枪械给出的原始冷却
 */
public record GunCooldownContext(@NotNull PlayerEntity shooter,
                                 @NotNull ItemStack stack,
                                 @Nullable PlayerEntity target,
                                 int baseCooldown) {
}
