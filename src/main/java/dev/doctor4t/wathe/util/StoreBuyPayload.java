package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.record.ShopPurchaseTracker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.jetbrains.annotations.NotNull;

public record StoreBuyPayload(int index) implements CustomPayload {
    public static final Id<StoreBuyPayload> ID = new Id<>(Wathe.id("storebuy"));
    public static final PacketCodec<PacketByteBuf, StoreBuyPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, StoreBuyPayload::index, StoreBuyPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static class Receiver implements ServerPlayNetworking.PlayPayloadHandler<StoreBuyPayload> {
        @Override
        public void receive(@NotNull StoreBuyPayload payload, ServerPlayNetworking.@NotNull Context context) {
            PlayerShopComponent component = PlayerShopComponent.KEY.get(context.player());
            int before = component.balance;
            ShopPurchaseTracker.clear(context.player());
            component.tryBuy(payload.index());

            /*
             * 商店购买真正成功的判断条件，仍然以余额是否减少为准。
             * 但具体“买到的是什么”优先使用 ShopPurchaseTracker 里由实际购买逻辑回填的结果，
             * 这样扩展职业模组替换了商店内容后，回放就不会再按原版格子号误报商品。
             */
            if (component.balance < before) {
                ShopPurchaseTracker.PendingShopPurchase purchase = ShopPurchaseTracker.consume(context.player());
                if (purchase != null) {
                    int resolvedIndex = purchase.index() >= 0 ? purchase.index() : payload.index();
                    int resolvedPrice = purchase.pricePaid() > 0 ? purchase.pricePaid() : before - component.balance;
                    GameRecordManager.recordShopPurchase(context.player(), purchase.stack(), resolvedIndex, resolvedPrice);
                    return;
                }

                if (payload.index() >= 0 && payload.index() < GameConstants.SHOP_ENTRIES.size()) {
                    GameRecordManager.recordShopPurchase(
                            context.player(),
                            GameConstants.SHOP_ENTRIES.get(payload.index()),
                            payload.index(),
                            before - component.balance
                    );
                }
            }
        }
    }
}
