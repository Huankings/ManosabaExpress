package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Shadow
    public abstract boolean equals(KeyBinding other);

    @Unique
    private boolean wathe$isAlivePlayerInRunningGame() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || !WatheClient.isPlayerAliveAndInSurvival()) {
            return false;
        }

        /*
         * 跳跃限制是“Wathe 对局内规则”，
         * 不是对整个客户端会话的全局封禁。
         * 因此必须额外确认当前世界里的 Wathe 对局确实正在运行，
         * 这样大厅、切图、调试场景都不会被误伤。
         */
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(player.getWorld());
        return gameComponent != null && gameComponent.isRunning();
    }

    @Unique
    private boolean shouldSuppressKey() {
        if (!this.wathe$isAlivePlayerInRunningGame()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(client.player.getWorld());

        /*
         * 其余几个按键继续沿用 Wathe 原本的局内限制；
         * 只有 jumpKey 改成读取世界组件中的动态开关，
         * 从而支持通过 /wathe:allowjump 实时切换。
         */
        return this.equals(client.options.swapHandsKey) ||
                (!gameComponent.isAlivePlayerJumpAllowed() && this.equals(client.options.jumpKey)) ||
                this.equals(client.options.togglePerspectiveKey) ||
                this.equals(client.options.dropKey) ||
                this.equals(client.options.advancementsKey);
    }

    @ModifyReturnValue(method = "wasPressed", at = @At("RETURN"))
    private boolean wathe$restrainWasPressedKeys(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }

    @ModifyReturnValue(method = "isPressed", at = @At("RETURN"))
    private boolean wathe$restrainIsPressedKeys(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }

    @ModifyReturnValue(method = "matchesKey", at = @At("RETURN"))
    private boolean wathe$restrainMatchesKey(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }
}
