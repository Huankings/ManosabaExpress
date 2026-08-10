package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.collision.PlayerCollisionApi;
import dev.doctor4t.wathe.api.movement.PlayerMovementApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.GravityConfig;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.util.WatheMovementInputAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends EntityMixin implements WatheMovementInputAccess {
    @Unique
    private static final EntityAttributeModifier KNIFE_KNOCKBACK_MODIFIER = new EntityAttributeModifier(Wathe.id("knife_knockback_modifier"), 1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    @Unique
    private static final net.minecraft.util.Identifier MAP_GRAVITY_MODIFIER_ID = Wathe.id("map_gravity_modifier");
    @Unique
    private float wathe$lastGravityMultiplier = Float.NaN;

    @Shadow
    protected boolean jumping;

    @Shadow
    public float sidewaysSpeed;

    @Shadow
    public float forwardSpeed;

    @Shadow
    public abstract void playSound(@Nullable SoundEvent sound);

    @Shadow
    public abstract @Nullable EntityAttributeInstance getAttributeInstance(RegistryEntry<EntityAttribute> attribute);

    @Inject(method = "tick", at = @At("HEAD"))
    public void wathe$addKnockbackWithKnife(CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            EntityAttributeModifier v = new EntityAttributeModifier(Wathe.id("knife_knockback_modifier"), .5f, EntityAttributeModifier.Operation.ADD_VALUE);
            updateAttribute(player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK), v, player.getMainHandStack().isOf(WatheItems.KNIFE));
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void wathe$applyMapGravityMultiplier(CallbackInfo ci) {
        if (!((Object) this instanceof PlayerEntity player)) {
            return;
        }

        EntityAttributeInstance gravityAttribute = player.getAttributeInstance(EntityAttributes.GENERIC_GRAVITY);
        if (gravityAttribute == null) {
            return;
        }

        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(player.getWorld());
        float targetMultiplier = 1.0f;
        if (gameComponent != null && gameComponent.isRunning() && GameFunctions.isPlayerAliveAndSurvival(player)) {
            GravityConfig gravityConfig = MapEnhancementsWorldComponent.KEY.get(player.getWorld()).getGravityConfig();
            targetMultiplier = gravityConfig.gravityMultiplier();
        }

        if (Float.compare(targetMultiplier, this.wathe$lastGravityMultiplier) == 0) {
            return;
        }

        // 地图重力只在本 tick 状态下临时挂载，离开对局后会恢复原版重力。
        if (gravityAttribute.hasModifier(MAP_GRAVITY_MODIFIER_ID)) {
            gravityAttribute.removeModifier(MAP_GRAVITY_MODIFIER_ID);
        }
        if (Float.compare(targetMultiplier, 1.0f) != 0) {
            gravityAttribute.addTemporaryModifier(new EntityAttributeModifier(
                    MAP_GRAVITY_MODIFIER_ID,
                    targetMultiplier - 1.0f,
                    EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
            ));
        }
        this.wathe$lastGravityMultiplier = targetMultiplier;
    }

    @Inject(method = "pushAway(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void wathe$skipNoCollisionLivingPush(Entity other, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity selfPlayer
                && other instanceof PlayerEntity otherPlayer
                && PlayerCollisionApi.suppressesPush(selfPlayer, otherPlayer)) {
            /*
             * LivingEntity#pushAway 是活体扫描附近实体后发起推挤的入口。
             * Entity#pushAwayFrom 已经有底层兜底，这里提前取消一次，让 NO_COLLISION 玩家不会产生任何原版轻推手感。
             */
            ci.cancel();
        }
    }

    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3d wathe$blockExhaustedHorizontalInput(Vec3d movementInput) {
        if ((Object) this instanceof PlayerEntity player && !PlayerMovementApi.canSelfMove(player)) {
            /*
             * 体力归零时只拦玩家自己的水平输入。
             * 这里不能清实体 velocity，也不能取消 travel 整个方法，否则击退、推挤、传送残留速度、
             * 水流/载具等外力位移都会被误删。保留 y 分量可以避免破坏下落、流体浮力等竖直处理。
             */
            return new Vec3d(0.0D, movementInput.y, 0.0D);
        }
        return movementInput;
    }

    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    private void wathe$blockExhaustedJump(CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player && !PlayerMovementApi.canJump(player)) {
            /*
             * 自主跳跃属于玩家输入行为，体力归零时直接取消。
             * 外部给玩家的向上速度不经过这里，因此爆炸/击退/传送等竖直位移仍然保留。
             */
            this.jumping = false;
            ci.cancel();
        }
    }

    @Override
    public boolean wathe$isTryingHorizontalSelfMove() {
        /*
         * 这两个字段是玩家本帧的水平移动输入，来源是 LivingEntity。
         * 体力低落惩罚只按“自主按键移动”扣体力，不能用 velocity 判断，
         * 否则击退、推挤、传送、水流等外力位移会被误认为玩家在走路。
         */
        return Math.abs(this.forwardSpeed) > 0.0F || Math.abs(this.sidewaysSpeed) > 0.0F;
    }

    @Unique
    private static void updateAttribute(EntityAttributeInstance attribute, EntityAttributeModifier modifier, boolean addOrKeep) {
        if (attribute != null) {
            boolean alreadyHasModifier = attribute.hasModifier(modifier.id());
            if (addOrKeep && !alreadyHasModifier) {
                attribute.addPersistentModifier(modifier);
            } else if (!addOrKeep && alreadyHasModifier) {
                attribute.removeModifier(modifier);
            }
        }
    }
}
