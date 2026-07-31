package dev.doctor4t.wathe.mixin.client;

import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 Wathe 的目标可见性 API 接进客户端实体选中流程。
 *
 * <p>这个 mixin 只处理玩家和玩家尸体，目的是替代扩展职业继续 mixin {@code LivingEntity#canHit()}。
 * 它不会在服务端全局取消原版交互包；真正的玩法结算仍需要对应物品或 C2S receiver 再调用
 * {@link TargetVisibilityApi} 重新校验。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTargetVisibilityMixin {
    @Inject(method = "canHit", at = @At("HEAD"), cancellable = true)
    private void wathe$hideTargetVisibilityEntities(CallbackInfoReturnable<Boolean> cir) {
        ClientPlayerEntity viewer = MinecraftClient.getInstance().player;
        if (viewer == null) {
            return;
        }

        Object self = this;
        if (self instanceof PlayerBodyEntity body && !TargetVisibilityApi.canTargetBody(viewer, body)) {
            cir.setReturnValue(false);
        } else if (self instanceof PlayerEntity target && !TargetVisibilityApi.canTargetPlayer(viewer, target)) {
            cir.setReturnValue(false);
        }
    }
}
