package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.api.client.input.ClientInputApi;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void wathe$applyKeyboardRules(
            long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci
    ) {
        if (ClientInputApi.shouldBlockKeyboard(key, scanCode, action, modifiers)) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "processF3")
    private boolean wathe$disableF3Keybinds(int key, Operation<Boolean> original) {
        if (WatheClient.isPlayerAliveAndInSurvival()) {
            return key == 293 ? original.call(key) : false;
        } else {
            return original.call(key);
        }
    }
}
