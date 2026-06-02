package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.bed.BedEffectRegistry;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.cca.PlayerPoisonComponent;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class PoisonUtils {
    public static float getFovMultiplier(float tickDelta, PlayerPoisonComponent poisonComponent) {
        if (!poisonComponent.pulsing) return 1f;

        poisonComponent.pulseProgress += tickDelta * 0.1f;

        if (poisonComponent.pulseProgress >= 1f) {
            poisonComponent.pulsing = false;
            poisonComponent.pulseProgress = 0f;
            return 1f;
        }

        float maxAmplitude = 0.1f;
        float minAmplitude = 0.025f;

        float result = getResult(poisonComponent, minAmplitude, maxAmplitude);

        return result;
    }

    private static float getResult(PlayerPoisonComponent poisonComponent, float minAmplitude, float maxAmplitude) {
        float amplitude = minAmplitude + (maxAmplitude - minAmplitude) * (1f - ((float) poisonComponent.poisonTicks / 1200f));

        float result;

        if (poisonComponent.pulseProgress < 0.25f) {
            result = 1f - amplitude * (float) Math.sin(Math.PI * (poisonComponent.pulseProgress / 0.25f));
        } else if (poisonComponent.pulseProgress < 0.5f) {
            result = 1f - amplitude * (float) Math.sin(Math.PI * ((poisonComponent.pulseProgress - 0.25f) / 0.25f));
        } else {
            result = 1f;
        }
        return result;
    }

    /**
     * 对旧调用方保留兼容入口。
     *
     * <p>床睡觉结算现在已经统一改走 BedEffectRegistry，
     * 因此这里仅作为旧代码的转发壳保留。</p>
     */
    public static void bedPoison(ServerPlayerEntity player) {
        BedEffectRegistry.triggerBedEffect(player);
    }

    /**
     * 真正执行“蝎子床触发后的中毒结算”。
     *
     * <p>现在蝎子只是 BedEffectRegistry 里的一个内置处理器，
     * 因此这里不再负责搜索哪张床有蝎子，而只负责蝎子命中后的后续效果。</p>
     */
    public static void applyScorpionBedEffect(ServerPlayerEntity player, @Nullable UUID poisoner) {
        World world = player.getEntityWorld();
        if (world.isClient) {
            return;
        }

        int poisonTicks = PlayerPoisonComponent.KEY.get(player).poisonTicks;

        NbtCompound extra = new NbtCompound();
        extra.putUuid("victim", player.getUuid());
        if (poisoner != null) {
            extra.putUuid("poisoner", poisoner);
        }

        GameRecordManager.recordGlobalEvent(
                player.getServerWorld(),
                Wathe.id("scorpion_sting"),
                poisoner == null ? null : player.getServer().getPlayerManager().getPlayer(poisoner),
                extra.copy()
        );

        if (poisonTicks == -1) {
            PlayerPoisonComponent.KEY.get(player).setDetailedPoisonTicks(
                    world.getRandom().nextBetween(PlayerPoisonComponent.clampTime.getLeft(), PlayerPoisonComponent.clampTime.getRight()),
                    poisoner,
                    GameConstants.DeathReasons.BED_POISON,
                    extra
            );
        } else {
            PlayerPoisonComponent.KEY.get(player).setDetailedPoisonTicks(
                    MathHelper.clamp(poisonTicks - world.getRandom().nextBetween(100, 300), 0, PlayerPoisonComponent.clampTime.getRight()),
                    poisoner,
                    GameConstants.DeathReasons.BED_POISON,
                    extra
            );
        }

        ServerPlayNetworking.send(
                player, new PoisonOverlayPayload("game.player.stung")
        );
    }


    public record PoisonOverlayPayload(String translationKey) implements CustomPayload {
        public static final Id<PoisonOverlayPayload> ID =
                new Id<>(Wathe.id("poisoned_text"));

        public static final PacketCodec<RegistryByteBuf, PoisonOverlayPayload> CODEC =
                PacketCodec.of(PoisonOverlayPayload::write, PoisonOverlayPayload::read);

        private void write(RegistryByteBuf buf) {
            buf.writeString(translationKey);
        }

        private static PoisonOverlayPayload read(RegistryByteBuf buf) {
            return new PoisonOverlayPayload(buf.readString());
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        public static class Receiver implements ClientPlayNetworking.PlayPayloadHandler<PoisonOverlayPayload> {
            @Override
            public void receive(@NotNull PoisonOverlayPayload payload, ClientPlayNetworking.@NotNull Context context) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> client.inGameHud.setOverlayMessage(Text.translatable(payload.translationKey()), false));
            }
        }
    }
}
