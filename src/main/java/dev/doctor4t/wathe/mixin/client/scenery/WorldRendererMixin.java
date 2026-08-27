package dev.doctor4t.wathe.mixin.client.scenery;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.doctor4t.wathe.api.client.fog.FogOverrideApi;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void wathe$beginFogFrame(
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        /*
         * 先清掉上一帧的最终 override，保证下面的 provider 读取到的是本帧原版/地图写入的
         * 基础雾值，而不是上一帧的杰森雾距。Iris 的 uniform 更新发生在 applyFog 之后，
         * 所以清理不会影响最终 shaderpack 读取到的结果。
         */
        FogOverrideApi.beginFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wathe$endFogFrame(
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightmapTextureManager lightmapTextureManager,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        /*
         * 世界渲染结束后立刻切到 GUI 使用的无雾状态。
         * HUD、聊天栏、tab 列表和后续 GUI 渲染都发生在这里之后；这些文字 shader 也读取
         * FogStart/FogColor，如果继续沿用世界雾，白天会被雾色冲白，晚上会被雾色压黑。
         */
        FogOverrideApi.endFrame();
    }

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

        /*
         * 原版、液体状态和 Wathe 地图雾都已经完成后，才统一解析扩展雾效。
         * 这样地图雾仍然是基础值，杰森能力等临时效果只覆盖最终 start/end，
         * Iris/Sodium 后续读取 RenderSystem getter 时也会得到同一份最终状态。
         */
        FogOverrideApi.applyOverrides(camera, tickDelta);
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
