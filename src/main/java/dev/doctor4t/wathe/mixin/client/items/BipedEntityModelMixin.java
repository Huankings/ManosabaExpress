package dev.doctor4t.wathe.mixin.client.items;

import dev.doctor4t.wathe.api.client.mood.PsychosisItemApi;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BipedEntityModel.class)
public class BipedEntityModelMixin<T extends LivingEntity> {
    @Shadow
    @Final
    public ModelPart leftArm;

    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart head;

    @Inject(method = "positionRightArm", at = @At("TAIL"))
    private void wathe$holdRevolverRightArm(T entity, CallbackInfo ci) {
        if (isHoldingGunInArm(entity, Arm.RIGHT)) {
            holdGun(this.rightArm, this.head, true);
        }
    }

    @Inject(method = "positionLeftArm", at = @At("TAIL"))
    private void wathe$wathe$holdRevolverLeftArm(T entity, CallbackInfo ci) {
        if (isHoldingGunInArm(entity, Arm.LEFT)) {
            holdGun(this.leftArm, this.head, false);
        }
    }

    @Unique
    private boolean isHoldingGunInArm(T entity, Arm arm) {
        /* 显式姿势优先于 Wathe 默认枪械托举，避免扩展 provider 的姿势被这里二次覆盖。 */
        Hand poseHand = entity.getMainArm() == arm ? Hand.MAIN_HAND : Hand.OFF_HAND;
        if (PsychosisItemApi.resolveRenderArmPose(MinecraftClient.getInstance().player, entity, poseHand) != null) {
            return false;
        }
        if (entity.getMainArm() == arm) {
            return getVisualMainHandStack(entity).isIn(WatheItemTags.GUNS);
        }

        return entity.getOffHandStack().isIn(WatheItemTags.GUNS);
    }

    @Unique
    private static ItemStack getVisualMainHandStack(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return entity.getMainHandStack();
        }

        return PsychosisItemApi.resolveRenderStack(client.player, entity, Hand.MAIN_HAND, entity.getMainHandStack());
    }

    @Unique
    private static void holdGun(ModelPart arm, ModelPart head, boolean rightArmed) {
        arm.yaw = (rightArmed ? -0.3F : 0.3F) + head.yaw;
        arm.pitch = (float) (-Math.PI / 2) + head.pitch + 0.1F;
    }
}
