package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.doctor4t.wathe.api.client.input.ClientInputApi;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @ModifyReturnValue(method = "wasPressed", at = @At("RETURN"))
    private boolean wathe$restrainWasPressedKeys(boolean original) {
        return ClientInputApi.resolveKeyBinding(
                (KeyBinding) (Object) this, ClientInputApi.KeyQuery.WAS_PRESSED, original, -1, -1
        );
    }

    @ModifyReturnValue(method = "isPressed", at = @At("RETURN"))
    private boolean wathe$restrainIsPressedKeys(boolean original) {
        return ClientInputApi.resolveKeyBinding(
                (KeyBinding) (Object) this, ClientInputApi.KeyQuery.IS_PRESSED, original, -1, -1
        );
    }

    @ModifyReturnValue(method = "matchesKey", at = @At("RETURN"))
    private boolean wathe$restrainMatchesKey(boolean original, int keyCode, int scanCode) {
        return ClientInputApi.resolveKeyBinding(
                (KeyBinding) (Object) this, ClientInputApi.KeyQuery.MATCHES_KEY, original, keyCode, scanCode
        );
    }
}
