package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.api.combat.KnifeCooldownApi;
import dev.doctor4t.wathe.api.combat.KnifeCooldownContext;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.NotNull;

public record KnifeStabPayload(int target) implements CustomPayload {
    public static final Id<KnifeStabPayload> ID = new Id<>(Wathe.id("knifestab"));
    public static final PacketCodec<PacketByteBuf, KnifeStabPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, KnifeStabPayload::target, KnifeStabPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static class Receiver implements ServerPlayNetworking.PlayPayloadHandler<KnifeStabPayload> {
        @Override
        public void receive(@NotNull KnifeStabPayload payload, ServerPlayNetworking.@NotNull Context context) {
            ServerPlayerEntity player = context.player();
            /*
             * 在进入死亡流程前保留主手快照。DeathApi 的某些处理器可能会改变物品或职业状态，
             * 而冷却 modifier 需要看到“这次刺杀发生时”的物品数据。快照只用于判定，
             * 真正写入冷却的对象仍是 WatheItems.KNIFE，保持原有行为。
             */
            var usedStack = player.getMainHandStack().copy();
            if (!(player.getServerWorld().getEntityById(payload.target()) instanceof PlayerEntity target)) return;
            if (target.distanceTo(player) > 3.0) return;
            if (!TargetVisibilityApi.canAttackPlayer(player, target)) return;

            // 先记录“匕首命中”，再进入真正的死亡判定。
            if (target instanceof ServerPlayerEntity serverTarget) {
                GameRecordManager.recordItemHit(
                        player,
                        player.getMainHandStack(),
                        GameConstants.DeathReasons.KNIFE,
                        serverTarget,
                        null
                );
            }

            GameFunctions.killPlayer(target, true, player, GameConstants.DeathReasons.KNIFE);
            target.playSound(WatheSounds.ITEM_KNIFE_STAB, 1.0f, 1.0f);
            player.swingHand(Hand.MAIN_HAND);

            GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
            boolean defaultCooldownApplies = !player.isCreative() && game.getGameMode() != WatheGameModes.LOOSE_ENDS;
            int baseCooldown = GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.KNIFE, 0);
            int finalCooldown = KnifeCooldownApi.modifyCooldown(new KnifeCooldownContext(
                    player,
                    target,
                    usedStack,
                    game,
                    baseCooldown,
                    defaultCooldownApplies
            ));

            /*
             * modifier 故意放在 GameFunctions.killPlayer 之后：杰森这类机制需要等 DeathApi
             * 确认“本次确实成功处决重伤目标”才免除冷却，护盾或其它免死不能误触发。
             * 即使创造模式 / Loose Ends 不会真正写入冷却，也先计算一次，
             * 让扩展能够正确消费死亡流程中产生的一次性标记。
             */
            if (defaultCooldownApplies) {
                player.getItemCooldownManager().set(WatheItems.KNIFE, finalCooldown);
            }
        }
    }
}
