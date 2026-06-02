package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerGrenadeComponent;
import dev.doctor4t.wathe.index.WatheItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.jetbrains.annotations.NotNull;

public record GrenadeThrowModePayload(boolean directThrow) implements CustomPayload {
    public static final Id<GrenadeThrowModePayload> ID = new Id<>(Wathe.id("grenade_throw_mode"));
    public static final PacketCodec<PacketByteBuf, GrenadeThrowModePayload> CODEC = PacketCodec.tuple(PacketCodecs.BOOL, GrenadeThrowModePayload::directThrow, GrenadeThrowModePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static class Receiver implements ServerPlayNetworking.PlayPayloadHandler<GrenadeThrowModePayload> {
        @Override
        public void receive(@NotNull GrenadeThrowModePayload payload, ServerPlayNetworking.@NotNull Context context) {
            /*
             * 左键切模式只允许在“当前主手确实拿着手雷”的时候生效。
             *
             * 这样一来：
             * 1. 正常玩家操作时，只有手持手雷才会更新偏好；
             * 2. 客户端就算乱发包，也不能在没拿手雷时偷偷改状态；
             * 3. 服务端始终保留最终权威状态，并同步回客户端做持久保存。
             */
            if (!context.player().getMainHandStack().isOf(WatheItems.GRENADE)) {
                return;
            }

            PlayerGrenadeComponent.KEY.get(context.player()).setThrowMode(PlayerGrenadeComponent.ThrowMode.fromDirectThrow(payload.directThrow()));
        }
    }
}
