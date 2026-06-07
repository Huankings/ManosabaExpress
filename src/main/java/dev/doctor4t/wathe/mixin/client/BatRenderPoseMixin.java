package dev.doctor4t.wathe.mixin.client;

import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.CrossbowPosing;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 资源包 / ETF / EMF 可能会覆盖掉仅依赖 arm pose 的静态举棒动作，
 * 所以这里在玩家模型已经完成 setAngles 之后再补一次托举姿态，
 * 确保第三人称和他人视角下拿着球棒时仍会稳定举起双臂。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class BatRenderPoseMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow
    protected M model;

    @Inject(
            method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Lnet/minecraft/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void wathe$applyBatHoldPose(LivingEntity entity,
                                        float entityYaw,
                                        float tickDelta,
                                        MatrixStack matrices,
                                        VertexConsumerProvider vertexConsumers,
                                        int light,
                                        CallbackInfo ci) {
        if (!(entity instanceof AbstractClientPlayerEntity player)) {
            return;
        }
        if (!player.getMainHandStack().isOf(WatheItems.BAT)) {
            return;
        }
        if (!(this.model instanceof BipedEntityModel<?> bipedModel)) {
            return;
        }

        boolean rightArmed = player.getMainArm() == Arm.RIGHT;
        if (rightArmed) {
            CrossbowPosing.hold(bipedModel.rightArm, bipedModel.leftArm, bipedModel.head, true);
        } else {
            CrossbowPosing.hold(bipedModel.leftArm, bipedModel.rightArm, bipedModel.head, false);
        }
    }
}
