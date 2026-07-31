package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.api.client.gui.CrosshairHudApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.item.DerringerItem;
import dev.doctor4t.wathe.item.KnifeItem;
import dev.doctor4t.wathe.item.RevolverItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.EntityHitResult;
import org.jetbrains.annotations.NotNull;

public class CrosshairRenderer {
    public static void renderCrosshair(@NotNull MinecraftClient client, @NotNull ClientPlayerEntity player, DrawContext context, RenderTickCounter tickCounter) {
        if (!client.options.getPerspective().isFirstPerson()) return;
        ItemStack mainHandStack = player.getMainHandStack();
        float tickDelta = tickCounter.getTickDelta(true);
        CrosshairHudApi.Context apiContext = new CrosshairHudApi.Context(client, player, context, tickCounter, mainHandStack, tickDelta);

        /*
         * 准心图标是非常靠近玩家操作反馈的客户端提示，但它不能决定真实命中结果。
         * 这里先给扩展职业一个公开 provider 链，让刺刀、赏金枪、变形试剂等物品可以
         * 接管“这一帧该怎么显示”；真正的距离、冷却、职业和目标合法性仍然必须在服务端
         * 物品逻辑或 C2S 包接收器里重新校验。
         */
        if (CrosshairHudApi.renderProvider(apiContext) == CrosshairHudApi.Result.PASS) {
            renderDefaultCrosshair(apiContext);
        }

        /*
         * Overlay 只适合“默认准心之后额外补小进度条”的场景。
         * 它不参与短路，避免时停者怀表这类后置提示为了画一条条而继续 mixin 本 renderer。
         */
        CrosshairHudApi.renderOverlays(apiContext);
    }

    private static void renderDefaultCrosshair(@NotNull CrosshairHudApi.Context context) {
        boolean target = false;
        ClientPlayerEntity player = context.player();
        ItemStack mainHandStack = context.mainHandStack();
        if (mainHandStack.isOf(WatheItems.REVOLVER) && !player.getItemCooldownManager().isCoolingDown(mainHandStack.getItem()) && RevolverItem.getGunTarget(player) instanceof EntityHitResult) {
            target = true;
        } else if (mainHandStack.isOf(WatheItems.DERRINGER) && !player.getItemCooldownManager().isCoolingDown(mainHandStack.getItem()) && DerringerItem.getGunTarget(player) instanceof EntityHitResult) {
            target = true;
        } else if (mainHandStack.isOf(WatheItems.KNIFE)) {
            ItemCooldownManager manager = player.getItemCooldownManager();
            if (!manager.isCoolingDown(WatheItems.KNIFE) && KnifeItem.getKnifeTarget(player) instanceof EntityHitResult) {
                target = true;
                CrosshairHudApi.renderKnifeProgressCrosshair(context, true, true, 1.0F);
            } else {
                float progress = 1.0F - manager.getCooldownProgress(WatheItems.KNIFE, context.tickDelta());
                CrosshairHudApi.renderKnifeProgressCrosshair(context, false, false, progress);
            }
            return;
        } else if (PsychoModeApi.isMeleeKillWeapon(player, mainHandStack)) {
            if (player.getAttackCooldownProgress(context.tickDelta()) >= 1f
                    && context.client().crosshairTarget instanceof EntityHitResult result
                    && result.getEntity() instanceof PlayerEntity targetPlayer
                    && TargetVisibilityApi.canTargetPlayer(player, targetPlayer)) {
                target = true;
                CrosshairHudApi.renderBatProgressCrosshair(context, true, true, 1.0F);
            } else {
                float progress = player.getAttackCooldownProgress(context.tickDelta());
                CrosshairHudApi.renderBatProgressCrosshair(context, false, false, progress);
            }
            return;
        }
        CrosshairHudApi.renderStandardCrosshair(context, target);
    }
}
