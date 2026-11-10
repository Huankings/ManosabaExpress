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
             * 客户端射线只负责准备实体 id，不能作为玩法权威。这里继续校验主手物品、
             * 目标、距离与 ATTACK 权限，但不能要求攻击者仍按玩法存活：Wathe 原有调试/旁观语义
             * 允许“非存活但仍能使用物品”的玩家用匕首击杀存活玩家，并且不受匕首 CD 限制。
             *
             * 冷却校验因此只约束玩法存活攻击者。PlayerLifeStateApi 特殊授权的
             * spectator/creative 会继续被 isPlayerAliveAndSurvival 视为存活，仍走正常冷却规则；
             * 普通 creative 等非存活状态则恢复旧版的无冷却刺击行为。
             */
            boolean attackerAliveInGame = GameFunctions.isPlayerAliveAndSurvival(player);
            if (!usedStack.isOf(WatheItems.KNIFE)
                    || (attackerAliveInGame
                    && player.getItemCooldownManager().isCoolingDown(WatheItems.KNIFE))) {
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
