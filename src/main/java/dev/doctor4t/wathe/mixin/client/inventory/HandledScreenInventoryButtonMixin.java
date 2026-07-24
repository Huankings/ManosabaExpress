package dev.doctor4t.wathe.mixin.client.inventory;

import dev.doctor4t.wathe.api.client.inventory.InventoryButtonApi;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 普通背包没有自己声明 removed()，关闭生命周期实际落在 HandledScreen 上。
 *
 * <p>这里统一兜底清理 Wathe InventoryButtonApi 的 screen 状态；没有注册过背包按钮状态的 screen
 * 调用 closeScreen 是无副作用的，因此不会影响箱子、工作台等其它 handled screen。</p>
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenInventoryButtonMixin {
    @Inject(method = "removed", at = @At("HEAD"))
    private void wathe$closeInventoryButtons(CallbackInfo ci) {
        InventoryButtonApi.closeScreen((HandledScreen<?>) (Object) this);
    }
}
