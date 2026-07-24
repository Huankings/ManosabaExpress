package dev.doctor4t.wathe.mixin.client.inventory;

import dev.doctor4t.wathe.api.client.inventory.InventoryButtonApi;
import dev.doctor4t.wathe.api.client.inventory.InventoryScreenType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends AbstractInventoryScreen<PlayerScreenHandler> {
    public InventoryScreenMixin(PlayerScreenHandler screenHandler, PlayerInventory playerInventory, Text text) {
        super(screenHandler, playerInventory, text);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void wathe$initInventoryButtons(CallbackInfo ci) {
        if (this.client == null) {
            return;
        }
        InventoryButtonApi.initializeScreen(
                this,
                InventoryScreenType.VANILLA,
                this.client.player,
                this.textRenderer,
                widget -> this.addDrawableChild(widget),
                this.width,
                this.height,
                this.x,
                this.y,
                this.backgroundWidth,
                this.backgroundHeight
        );
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void wathe$renderInventoryButtons(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        InventoryButtonApi.renderScreen(this, context, mouseX, mouseY, delta);
    }

    @Inject(method = "handledScreenTick", at = @At("TAIL"))
    private void wathe$tickInventoryButtons(CallbackInfo ci) {
        InventoryButtonApi.tickScreen(this);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wathe$keepInventoryOpenForButtons(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.client != null
                && this.client.options.inventoryKey.matchesKey(keyCode, scanCode)
                && !InventoryButtonApi.allowInventoryKeyClose(this, keyCode, scanCode)) {
            cir.setReturnValue(true);
        }
    }
}
