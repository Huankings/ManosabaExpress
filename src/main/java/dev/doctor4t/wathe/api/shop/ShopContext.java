package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 构建商店条目时的上下文。
 *
 * <p>它主要提供给 {@link ShopModifier} 使用：修改器可以知道当前玩家职业、
 * 是否已经命中了某个职业专属商店，从而决定要不要追加商品或调整价格。</p>
 */
public record ShopContext(
        @NotNull PlayerEntity player,
        @NotNull GameWorldComponent gameWorld,
        @Nullable Role role,
        boolean roleSpecificShop
) {
}
