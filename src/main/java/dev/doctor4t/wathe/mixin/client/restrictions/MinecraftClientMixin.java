package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.doctor4t.wathe.api.client.input.ClientInputApi;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow
    @Nullable
    public ClientPlayerEntity player;

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void wathe$runInputTickHandlers(CallbackInfo ci) {
        ClientInputApi.runInputTickHandlers();
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void wathe$applyAttackRules(CallbackInfoReturnable<Boolean> cir) {
        ClientInputApi.ActionResult result = ClientInputApi.resolveAction(ClientInputApi.ClientAction.ATTACK, false);
        if (result.decision() == ClientInputApi.Decision.BLOCK) {
            cir.setReturnValue(false);
        } else if (result.decision() == ClientInputApi.Decision.OVERRIDE) {
            cir.setReturnValue(result.value());
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void wathe$applyItemUseRules(CallbackInfo ci) {
        ClientInputApi.ActionResult result = ClientInputApi.resolveAction(ClientInputApi.ClientAction.ITEM_USE, false);
        if (result.decision() == ClientInputApi.Decision.BLOCK || result.decision() == ClientInputApi.Decision.OVERRIDE) {
            ci.cancel();
        }
    }

    @Inject(method = "doItemPick", at = @At("HEAD"), cancellable = true)
    private void wathe$applyItemPickRules(CallbackInfo ci) {
        ClientInputApi.ActionResult result = ClientInputApi.resolveAction(ClientInputApi.ClientAction.ITEM_PICK, false);
        if (result.decision() == ClientInputApi.Decision.BLOCK || result.decision() == ClientInputApi.Decision.OVERRIDE) {
            ci.cancel();
        }
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void wathe$applyBlockBreakingRules(boolean breaking, CallbackInfo ci) {
        ClientInputApi.ActionResult result = ClientInputApi.resolveAction(ClientInputApi.ClientAction.BLOCK_BREAKING, breaking);
        if (result.decision() == ClientInputApi.Decision.BLOCK || result.decision() == ClientInputApi.Decision.OVERRIDE) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "handleInputEvents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", ordinal = 1))
    private void wathe$replaceInventoryScreenWithLimitedInventoryScreen(MinecraftClient instance, Screen screen, Operation<Void> original) {
        if (WatheClient.gameComponent.getFade() > 0) {
            return;
        }

        original.call(instance, WatheClient.isPlayerAliveAndInSurvival() ? new LimitedInventoryScreen(this.player) : screen);
    }
}
