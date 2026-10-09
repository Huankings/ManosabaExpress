package dev.doctor4t.wathe.mixin.client.ui;

import dev.doctor4t.wathe.client.appearance.DebugIdentitySpectatorCommand;
import net.minecraft.client.gui.hud.spectator.SpectatorMenuCommand;
import net.minecraft.client.gui.hud.spectator.TeleportSpectatorMenu;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 旁观者玩家传送按钮的名称伪装；点击仍传送到真实 UUID，不改变服务端目标。 */
@Mixin(TeleportSpectatorMenu.class)
public abstract class TeleportSpectatorMenuMixin {
    @Inject(method = "method_46520", at = @At("RETURN"), cancellable = true)
    private static void wathe$resolveDebugIdentityName(PlayerListEntry entry,
                                                       CallbackInfoReturnable<SpectatorMenuCommand> cir) {
        Text disguised = dev.doctor4t.wathe.client.appearance.DebugDisguiseClientHandler
                .resolveIdentityName(entry.getProfile().getId());
        if (disguised != null && cir.getReturnValue() != null) {
            cir.setReturnValue(new DebugIdentitySpectatorCommand(cir.getReturnValue(), disguised));
        }
    }
}
