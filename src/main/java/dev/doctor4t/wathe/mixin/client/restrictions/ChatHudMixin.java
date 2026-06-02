package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChatHud.class)
public class ChatHudMixin {
    @WrapMethod(method = "render")
    public void wathe$disableChatRender(DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused, Operation<Void> original) {
        if (MinecraftClient.getInstance().player != null) {
            GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(MinecraftClient.getInstance().player.getWorld());
            if (!WatheClient.isPlayerAliveAndInSurvival() || !gameWorldComponent.isRunning()) {
                original.call(context, currentTick, mouseX, mouseY, focused);
            }
        }
    }
}
