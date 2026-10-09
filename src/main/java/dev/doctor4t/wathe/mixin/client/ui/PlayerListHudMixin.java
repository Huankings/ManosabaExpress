package dev.doctor4t.wathe.mixin.client.ui;

import dev.doctor4t.wathe.client.appearance.DebugDisguiseClientHandler;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;

/** Tab 玩家名伪装：只改 PlayerListHud 的显示文本，不改真实 PlayerListEntry 档案。 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void wathe$resolveDebugIdentityName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text disguised = DebugDisguiseClientHandler.resolveIdentityName(entry.getProfile().getId());
        if (disguised != null && cir.getReturnValue() != null) {
            /*
             * 必须保留原版已经算出的 Style，而不是直接返回新的 literal：
             * PlayerListHud 会在原版方法内部给 spectator 玩家设置斜体样式。
             * HEAD 注入直接短路原版逻辑会丢掉这个标记，导致真实旁观玩家和
             * 调试身份伪装玩家在 Tab 中出现不同的字体表现。
             */
            cir.setReturnValue(disguised.copy().setStyle(cir.getReturnValue().getStyle()));
        }
    }

    /**
     * 只包裹 Tab 自己的头像绘制调用，避免改写 PlayerListEntry 全局皮肤。
     * 这样玩家实体渲染、PlayerAppearanceApi 的“原始皮肤”解析和扩展 UI 都不会被身份伪装污染。
     */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/PlayerListEntry;getSkinTextures()Lnet/minecraft/client/util/SkinTextures;")
    )
    private SkinTextures wathe$resolveDebugIdentitySkin(PlayerListEntry entry, Operation<SkinTextures> original) {
        SkinTextures disguised = DebugDisguiseClientHandler.resolveIdentitySkin(entry.getProfile().getId());
        return disguised != null ? disguised : original.call(entry);
    }
}
