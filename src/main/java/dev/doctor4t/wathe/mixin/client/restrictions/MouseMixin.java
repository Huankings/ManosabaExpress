package dev.doctor4t.wathe.mixin.client.restrictions;

import dev.doctor4t.wathe.api.client.input.ClientInputApi;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse 的唯一公共输入调度注入点。 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void wathe$applyMouseButtonRules(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (ClientInputApi.shouldBlockMouseButton(button, action, modifiers)) {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void wathe$applyMouseScrollRules(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ClientInputApi.shouldBlockMouseScroll(horizontal, vertical)) {
            ci.cancel();
        }
    }
}
