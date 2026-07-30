package dev.doctor4t.wathe.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.client.invisibility.HeldItemInvisibilityApi;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.UUID;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    @Inject(method = "getArmPose", at = @At("TAIL"), cancellable = true)
    private static void wathe$customArmPose(AbstractClientPlayerEntity player,
                                          Hand hand, CallbackInfoReturnable<BipedEntityModel.ArmPose> cir) {
        if (PsychoModeApi.isMeleeKillWeapon(player, player.getStackInHand(hand)))
            cir.setReturnValue(BipedEntityModel.ArmPose.CROSSBOW_CHARGE);
    }

    @ModifyExpressionValue(method = "getArmPose", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getStackInHand(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;"))
    private static ItemStack wathe$changeNoteAndPsychosisItemsArmPos(ItemStack original, AbstractClientPlayerEntity player, Hand hand) {
        ItemStack stack = original;

        if (hand.equals(Hand.MAIN_HAND)) {
            if (stack.isOf(WatheItems.NOTE)) {
                stack = ItemStack.EMPTY;
            }
        }

        /*
         * 手持物真正被隐藏时，手臂姿势也要按“空手”处理。
         * 否则其他玩家虽然看不到物品，却仍会看到拿物品/使用物品的手臂动作。
         */
        stack = HeldItemInvisibilityApi.applyInvisibility(MinecraftClient.getInstance().player, player, hand, stack);

        if (hand.equals(Hand.MAIN_HAND)) {
            if (WatheClient.moodComponent != null && WatheClient.moodComponent.isLowerThanMid()) { // make sure it's only the main hand item that's being replaced
                HashMap<UUID, ItemStack> psychosisItems = WatheClient.moodComponent.getPsychosisItems();
                UUID uuid = player.getUuid();
                if (psychosisItems.containsKey(uuid)) {
                    return psychosisItems.get(uuid);
                }
            }
        }

        return stack;
    }
}
