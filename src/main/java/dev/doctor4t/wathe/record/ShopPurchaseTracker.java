package dev.doctor4t.wathe.record;

import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪“本次商店购买真正买到了什么”。
 *
 * <p>原版 Wathe 的回放记录是在 {@code StoreBuyPayload} 里按商店格子索引
 * 去 {@code GameConstants.SHOP_ENTRIES} 反查物品。这个做法只适合原版固定商店，
 * 一旦扩展职业模组在客户端 / 服务端把某个格子的商品替换成自定义内容，
 * 回放就会错误地继续显示“第 1 格=匕首、第 2 格=左轮”之类的原版商品。</p>
 *
 * <p>因此这里增加一层运行时追踪：
 * 1. 真正执行购买逻辑的一侧（Wathe 本体或扩展模组）在成功购买后调用 capture；
 * 2. {@code StoreBuyPayload} 在购买结束后 consume；
 * 3. 回放记录优先使用这里捕获到的“真实商品”，而不是再按格子号猜。</p>
 */
public final class ShopPurchaseTracker {
    private ShopPurchaseTracker() {
    }

    public record PendingShopPurchase(ItemStack stack, int index, int pricePaid) {
    }

    private static final Map<UUID, PendingShopPurchase> PENDING_PURCHASES = new ConcurrentHashMap<>();

    public static void clear(@Nullable PlayerEntity player) {
        if (player == null) {
            return;
        }
        PENDING_PURCHASES.remove(player.getUuid());
    }

    public static void captureSuccessfulPurchase(@Nullable PlayerEntity player, @NotNull ShopEntry entry, int index, int pricePaid) {
        captureSuccessfulPurchase(player, entry.stack(), index, pricePaid);
    }

    public static void captureSuccessfulPurchase(@Nullable PlayerEntity player, @NotNull ItemStack stack, int index, int pricePaid) {
        if (player == null || stack.isEmpty()) {
            return;
        }
        PENDING_PURCHASES.put(player.getUuid(), new PendingShopPurchase(stack.copy(), index, pricePaid));
    }

    public static @Nullable PendingShopPurchase consume(@Nullable PlayerEntity player) {
        if (player == null) {
            return null;
        }
        return PENDING_PURCHASES.remove(player.getUuid());
    }
}
