package dev.doctor4t.wathe.mixin.client.scenery;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.doctor4t.wathe.api.client.fog.FogOverrideApi;
import net.minecraft.client.render.FogShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 Wathe 每帧解析出的最终雾状态暴露给世界渲染阶段读取 RenderSystem getter 的渲染管线。
 *
 * <p>Iris 1.21.1 的 FogUniforms 正是通过这三个 getter 读取 legacy fog uniform，
 * 因此这里不需要依赖 Iris 私有类，也不会在没有安装 Iris 时产生缺失目标类问题。
 * 这份 override 会在 WorldRenderer 结束时清空，避免 GUI 文字 shader 继续套用世界雾颜色。</p>
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemFogStateMixin {
    @Inject(method = "getShaderFogStart", at = @At("RETURN"), cancellable = true)
    private static void wathe$overrideFogStart(CallbackInfoReturnable<Float> cir) {
        float resolved = FogOverrideApi.getResolvedStartOrNaN();
        if (Float.isFinite(resolved)) {
            cir.setReturnValue(resolved);
        }
    }

    @Inject(method = "getShaderFogEnd", at = @At("RETURN"), cancellable = true)
    private static void wathe$overrideFogEnd(CallbackInfoReturnable<Float> cir) {
        float resolved = FogOverrideApi.getResolvedEndOrNaN();
        if (Float.isFinite(resolved)) {
            cir.setReturnValue(resolved);
        }
    }

    @Inject(method = "getShaderFogShape", at = @At("RETURN"), cancellable = true)
    private static void wathe$overrideFogShape(CallbackInfoReturnable<FogShape> cir) {
        FogShape resolved = FogOverrideApi.getResolvedShapeOrNull();
        if (resolved != null) {
            cir.setReturnValue(resolved);
        }
    }
}
