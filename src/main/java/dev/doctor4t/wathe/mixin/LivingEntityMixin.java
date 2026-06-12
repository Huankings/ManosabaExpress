package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.GravityConfig;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends EntityMixin {
    @Unique
    private static final EntityAttributeModifier KNIFE_KNOCKBACK_MODIFIER = new EntityAttributeModifier(Wathe.id("knife_knockback_modifier"), 1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    @Unique
    private static final net.minecraft.util.Identifier MAP_GRAVITY_MODIFIER_ID = Wathe.id("map_gravity_modifier");
    @Unique
    private float wathe$lastGravityMultiplier = Float.NaN;

    @Shadow
    protected boolean jumping;

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
