package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.combat.KnifeAttackApi;
import dev.doctor4t.wathe.api.combat.KnifeStabContext;
import dev.doctor4t.wathe.api.combat.KnifeStabResult;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

public record KnifeStabPayload(int target) implements CustomPayload {
    public static final Id<KnifeStabPayload> ID = new Id<>(Wathe.id("knifestab"));
    public static final PacketCodec<PacketByteBuf, KnifeStabPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            KnifeStabPayload::target,
            KnifeStabPayload::new
    );

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

            /*
             * 客户端射线只负责准备实体 id，不能作为玩法权威。这里统一拒绝伪造或过期包：
             * 攻击者必须仍按玩法存活、主手确实是默认匕首、匕首没有处于冷却，
             * 目标必须存在、在三格内且允许 ATTACK。扩展非玩家目标也经过同一组基础校验。
             */
            if (!GameFunctions.isPlayerAliveAndSurvival(player)
                    || !usedStack.isOf(WatheItems.KNIFE)
                    || player.getItemCooldownManager().isCoolingDown(WatheItems.KNIFE)) {
                return;
            }
            Entity targetEntity = player.getServerWorld().getEntityById(payload.target());
            if (targetEntity == null
                    || targetEntity.distanceTo(player) > 3.0F
                    || !TargetVisibilityApi.canAttackEntity(player, targetEntity)) {
                return;
            }

            KnifeStabContext stabContext = new KnifeStabContext(
                    player,
                    usedStack,
                    targetEntity,
                    GameWorldComponent.KEY.get(player.getWorld())
            );
            KnifeStabResult apiResult = KnifeAttackApi.handleStab(stabContext);
            if (apiResult != KnifeStabResult.PASS) {
                return;
            }

            if (!(targetEntity instanceof PlayerEntity target)
                    || !GameFunctions.isPlayerAliveAndSurvival(target)) {
                return;
            }

            // 默认玩家刺杀仍保持“先记录命中 -> 死亡流程 -> 表现 -> 冷却流水线”的原有顺序。
            stabContext.recordPlayerHit(target);
            stabContext.killPlayer(target);
            stabContext.playDefaultFeedback();

            /*
             * 冷却故意放在 GameFunctions.killPlayer 之后：杰森这类机制需要等 DeathApi
             * 确认“本次确实成功处决重伤目标”才免除冷却，护盾或其它免死不能误触发。
             */
            stabContext.applyModifiedCooldown(target);
        }
    }
}
