package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.combat.GunShotApi;
import dev.doctor4t.wathe.api.combat.GunShotContext;
import dev.doctor4t.wathe.api.combat.GunShotResult;
import dev.doctor4t.wathe.api.combat.RevolverPenaltyContext;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

public record GunShootPayload(int target) implements CustomPayload {
    public static final Id<GunShootPayload> ID = new Id<>(Wathe.id("gunshoot"));
    public static final PacketCodec<PacketByteBuf, GunShootPayload> CODEC = PacketCodec.tuple(PacketCodecs.INTEGER, GunShootPayload::target, GunShootPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static class Receiver implements ServerPlayNetworking.PlayPayloadHandler<GunShootPayload> {
        @Override
        public void receive(@NotNull GunShootPayload payload, ServerPlayNetworking.@NotNull Context context) {
            ServerPlayerEntity player = context.player();
            ItemStack mainHandStack = player.getMainHandStack();

            /*
             * 先把枪击请求交给公开 API。
             *
             * 这一步刻意放在 WatheItemTags.GUNS 判断之前：
             * 扩展模组的自定义枪械不一定写进 Wathe 的标签，但它们仍然可以复用
             * GunShootPayload 这条客户端->服务端通道，并在 handler 里完整接管开火结算。
             */
            GunShotContext gunContext = new GunShotContext(player, mainHandStack, payload.target());
            GunShotResult apiResult = GunShotApi.handleShot(gunContext);
            if (apiResult != GunShotResult.PASS) {
                return;
            }

            if (!mainHandStack.isIn(WatheItemTags.GUNS)) return;
            if (player.getItemCooldownManager().isCoolingDown(mainHandStack.getItem())) return;

            gunContext.playDefaultClickSound();

            // cancel if derringer has been shot
            Boolean isUsed = mainHandStack.get(WatheDataComponentTypes.USED);
            if (mainHandStack.isOf(WatheItems.DERRINGER)) {
                if (isUsed == null) {
                    isUsed = false;
                }

                if (isUsed) {
                    return;
                }

                if (!player.isCreative()) mainHandStack.set(WatheDataComponentTypes.USED, true);
            }

            if (gunContext.playerTarget(65.0F, false) instanceof PlayerEntity target) {
                GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
                Item revolver = WatheItems.REVOLVER;

                boolean backfire = false;

                gunContext.recordGunHit(target);

                boolean targetNormallyInnocent = game.isInnocent(target);
                boolean shouldApplyInnocentPenalty = GunShotApi.shouldApplyInnocentRevolverPenalty(new RevolverPenaltyContext(
                        player,
                        target,
                        mainHandStack,
                        game,
                        targetNormallyInnocent
                ));

                /*
                 * 左轮的“误伤无辜者惩罚”现在先经过 GunShotApi 判定：
                 * 1. 默认情况下仍使用 Wathe 原本的 game.isInnocent(target)；
                 * 2. 执照恶棍、仇杀客目标、变形试剂伪装等扩展规则可以返回 SKIP；
                 * 3. 这里不改真正击杀目标的流程，只决定是否额外触发反火/掉枪/清心情。
                 */
                if (shouldApplyInnocentPenalty && !player.isCreative() && mainHandStack.isOf(revolver)) {
                    // 反火：无辜阵营开枪命中无辜者时，按对局反火概率改成击杀自己。
                    if (game.isInnocent(player) && player.getRandom().nextFloat() <= game.getBackfireChance()) {
                        backfire = true;
                        GameFunctions.killPlayer(player, true, player, GameConstants.DeathReasons.GUN);
                    } else {
                        /*
                         * 掉枪仍延迟 4 tick，保留 Wathe 原有手感和客户端同步节奏。
                         * 扩展如果希望完全跳过这段惩罚，应在 RevolverPenaltyRule 里返回 SKIP，
                         * 而不是再 mixin 这个内部 Scheduler lambda。
                         */
                        Scheduler.schedule(() -> {
                            if (!context.player().getInventory().contains((s) -> s.isIn(WatheItemTags.GUNS))) return;
                            player.getInventory().remove((s) -> s.isOf(revolver), 1, player.getInventory());
                            ItemEntity item = player.dropItem(revolver.getDefaultStack(), false, false);
                            if (item != null) {
                                item.setPickupDelay(10);
                                item.setThrower(player);
                            }
                            ServerPlayNetworking.send(player, new GunDropPayload());
                            PlayerMoodComponent.KEY.get(player).setMood(0);
                        }, 4);
                    }
                }

                if (!backfire) {
                    /*
                     * 真正的死亡仍统一走 GameFunctions.killPlayer。
                     * 这样扩展迁移到 DeathApi 后，枪击、刀击、巫毒等不同来源都会经过同一套
                     * 免死、护盾、回放、奖励、尸体生成和最终清理流程。
                     */
                    GameFunctions.killPlayer(target, true, player, GameConstants.DeathReasons.GUN);
                }
            }

            gunContext.playDefaultShootSound();

            gunContext.sendMuzzle();
            if (!player.isCreative()) {
                int baseCooldown = mainHandStack.isOf(WatheItems.REVOLVER)
                        ? GameConstants.getRevolverCooldown(player)
                        : GameConstants.ITEM_COOLDOWNS.getOrDefault(mainHandStack.getItem(), 0);
                /*
                 * 左轮现在改为“按玩家当前阵营单独结算冷却”。
                 *
                 * 这样同一把左轮：
                 * 1. 义警开火后会进入 8 秒冷却；
                 * 2. 平民开火后会进入 12 秒冷却；
                 * 3. 杀手开火后会进入 15 秒冷却；
                 * 4. 中立开火后会进入 20 秒冷却。
                 *
                 * 其他枪类（例如德林杰、扩展模组自定义枪）仍继续走各自原有的固定冷却表。
                 */
                int cooldown = gunContext.modifyCooldown(baseCooldown);
                player.getItemCooldownManager().set(mainHandStack.getItem(), cooldown);
            }
        }
    }
}
