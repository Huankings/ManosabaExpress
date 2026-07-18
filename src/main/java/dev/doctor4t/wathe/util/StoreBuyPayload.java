package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.shop.ShopPayment;
import dev.doctor4t.wathe.api.shop.ShopApi;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.record.ShopPurchaseTracker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
            List<ShopEntry> entriesBeforePurchase = ShopApi.getEntriesForPlayer(context.player());
            ShopPurchaseTracker.clear(context.player());
            component.tryBuy(payload.index());

            /*
             * 多货币后，购买成功不一定会减少金币（例如匕首可只扣任务币）。
             * 因此成功判断优先看 PlayerShopComponent 在真实成功分支里写入的追踪记录；
             * 旧的“金币减少”判断只作为兼容兜底保留。
             */
            ShopPurchaseTracker.PendingShopPurchase purchase = ShopPurchaseTracker.consume(context.player());
            if (purchase != null) {
                int resolvedIndex = purchase.index() >= 0 ? purchase.index() : payload.index();
                GameRecordManager.recordShopPurchase(context.player(), purchase.stack(), resolvedIndex, purchase.payment(), purchase.listedPrice());
                return;
            }

            if (component.balance < before) {
                if (payload.index() >= 0 && payload.index() < entriesBeforePurchase.size()) {
                    GameRecordManager.recordShopPurchase(
                            context.player(),
                            entriesBeforePurchase.get(payload.index()),
                            payload.index(),
                            ShopPayment.money(before - component.balance)
                    );
                }
            }
        }
    }
}
