package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 职业商店提供器。
 *
 * <p>扩展职业模组只需要注册“当前玩家应该看到哪些商品”，Wathe 会统一负责
 * 客户端渲染、服务端购买、扣钱、音效、同步和回放记录。这样每个扩展就不需要
 * 再 mixin {@code PlayerShopComponent} 与 {@code LimitedInventoryScreen} 各写一份流程。</p>
 */
@FunctionalInterface
public interface RoleShopProvider {
    /**
     * 返回玩家此刻应该看到的商店条目。
     *
     * <p>这里保留 {@link PlayerEntity} 参数，就是为了支持 Stalker 这类动态商店：
     * provider 可以读取玩家组件、世界配置、当前阶段等状态，实时生成不同商品。</p>
     */
    @NotNull List<ShopEntry> getShopEntries(@NotNull PlayerEntity player);

    /**
     * 尝试交付一次购买的商品。
     *
     * <p>默认实现只调用 {@link ShopEntry#onBuy(PlayerEntity)}。如果某个扩展职业需要
     * 特殊购买机制，可以覆写这个方法，但仍然只应该负责“商品是否成功交付”；
     * 扣钱、购买音效、失败音效、同步和回放记录仍交给 Wathe 的统一购买流程。</p>
     */
    default @NotNull ShopPurchaseResult purchase(@NotNull ShopPurchaseContext context) {
        return ShopApi.defaultPurchase(context);
    }
}
