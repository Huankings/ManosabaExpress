package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 单次购买尝试的上下文。
 *
 * <p>扩展 provider 若覆写购买行为，应通过这里读取余额、商品和职业状态；
 * 但不要直接扣钱或播放购买音效，避免和 Wathe 统一流程重复结算。</p>
 */
public record ShopPurchaseContext(
        @NotNull PlayerEntity player,
        @NotNull PlayerShopComponent shop,
        @NotNull ShopEntry entry,
        int index,
        @NotNull GameWorldComponent gameWorld,
        @Nullable Role role,
        boolean roleSpecificShop
) {
    public int balance() {
        return this.shop.balance;
    }
}
