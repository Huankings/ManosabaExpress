package dev.doctor4t.wathe.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.doctor4t.wathe.api.client.appearance.PlayerAppearanceApi;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin extends PlayerEntity {
    public AbstractClientPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile gameProfile) {
        super(world, pos, yaw, gameProfile);
    }

    /*
     * 这里保留这个 mixin 只是为了兼容旧版结构，真正的 FOV 修正已经下放到
     * GameRenderer.updateFovMultiplier() 的调用点上。
     *
     * 原因是 1.21.1 下这层目标虽然能编译通过，但在实际运行链路里没有稳定触发，
     * 所以继续把效果挂在这里会出现“只看见 pulse 日志、却看不到 client fov 日志”的情况。
     */

    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void wathe$resolveAppearanceApiSkinTextures(CallbackInfoReturnable<SkinTextures> cir) {
        SkinTextures overrideSkin = PlayerAppearanceApi.resolvePlayerSkin((AbstractClientPlayerEntity) (Object) this);
        if (overrideSkin != null) {
            /*
             * 所有“玩家看起来像谁”的扩展都在这里统一收口。
             * 原版 renderer 选 slim/classic、玩家本体贴图、披风/鞘翅、第一人称手臂都会读取 getSkinTextures()，
             * 因此只改这一层即可避免多个扩展同时 mixin 渲染器造成冲突。
             */
            cir.setReturnValue(overrideSkin);
        }
    }
}
