package dev.doctor4t.wathe.mixin.client.scenery;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.doctor4t.wathe.cca.TrainWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.client.util.AlwaysVisibleFrustum;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.FogConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.entity.effect.StatusEffects;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "method_52816", at = @At(value = "RETURN"), cancellable = true)
    private static void wathe$setFrustumToAlwaysVisible(Frustum frustum, @NotNull CallbackInfoReturnable<Frustum> cir) {
        // 只有移动列车地图需要禁用普通视锥剔除；静态地图保留原版剔除，避免无意义渲染。
        if (WatheClient.isTrainMoving()) {
            cir.setReturnValue(new AlwaysVisibleFrustum(frustum));
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V"))
    public void wathe$disableSky(WorldRenderer instance, Matrix4f matrix4f, Matrix4f projectionMatrix, float tickDelta, Camera camera, boolean thickFog, Runnable fogCallback, Operation<Void> original) {
        if (!WatheClient.isTrainMoving() || (WatheClient.trainComponent != null && WatheClient.trainComponent.getTimeOfDay() == TrainWorldComponent.TimeOfDay.SUNDOWN)) {
            original.call(instance, matrix4f, projectionMatrix, tickDelta, camera, thickFog, fogCallback);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/BackgroundRenderer;applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;FZF)V"))
    public void wathe$applyBlizzardFog(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance, boolean thickFog, float tickDelta, Operation<Void> original) {
        var player = MinecraftClient.getInstance().player;
        FogConfig fogConfig = WatheClient.mapEnhancementsWorldComponent != null
                ? WatheClient.mapEnhancementsWorldComponent.getFogConfig()
                : FogConfig.DEFAULT;

        if (WatheClient.trainComponent != null
                && WatheClient.trainComponent.isFoggy()
                && fogConfig.enabled()
                && player != null
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            if (WatheClient.isTrainMoving()) {
                wathe$doFog(fogConfig.start(), fogConfig.endMoving());
            } else {
                wathe$doFog(fogConfig.start(), fogConfig.endStationary());
            }
        } else {
            original.call(camera, fogType, viewDistance, thickFog, tickDelta);
        }
    }

    @Unique
    private static void wathe$doFog(float fogStart, float fogEnd) {
        BackgroundRenderer.FogData fogData = new BackgroundRenderer.FogData(BackgroundRenderer.FogType.FOG_SKY);

        fogData.fogStart = fogStart;
        fogData.fogEnd = fogEnd;

        fogData.fogShape = FogShape.SPHERE;

        RenderSystem.setShaderFogStart(fogData.fogStart);
        RenderSystem.setShaderFogEnd(fogData.fogEnd);
        RenderSystem.setShaderFogShape(fogData.fogShape);
    }

}
