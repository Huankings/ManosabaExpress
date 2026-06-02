package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.bed.BedEffectRegistry;
import dev.doctor4t.wathe.api.event.AllowPlayerPunching;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerPoisonComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.item.CocktailItem;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.Scheduler;
import dev.doctor4t.wathe.util.TrayEffectUtils;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity {
    @Shadow
    public abstract float getAttackCooldownProgress(float baseTime);

    @Unique
    private float sprintingTicks;
    @Unique
    private Scheduler.ScheduledTask poisonSleepTask;

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }


    @ModifyReturnValue(method = "getMovementSpeed", at = @At("RETURN"))
    public float wathe$overrideMovementSpeed(float original) {
        if (GameFunctions.isPlayerAliveAndSurvival((PlayerEntity) (Object) this)) {
            return this.isSprinting() ? 0.1f : 0.07f;
        } else {
            return original;
        }
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    public void wathe$limitSprint(CallbackInfo ci) {
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(this.getWorld());
        if (GameFunctions.isPlayerAliveAndSurvival((PlayerEntity) (Object) this) && gameComponent != null && gameComponent.isRunning()) {
            Role role = gameComponent.getRole((PlayerEntity) (Object) this);
            if (role != null && role.getMaxSprintTime() >= 0) {
                if (this.isSprinting()) {
                    sprintingTicks = Math.max(sprintingTicks - 1, 0);//体力减少速度
                } else {
                    sprintingTicks = Math.min(sprintingTicks + 0.8f, role.getMaxSprintTime());//体力回复速度
                }

                if (sprintingTicks <= 0) {
                    this.setSprinting(false);
                }
            }
        }
    }

    @WrapMethod(method = "attack")
    public void attack(Entity target, Operation<Void> original) {
        PlayerEntity self = (PlayerEntity) (Object) this;

        if (getMainHandStack().isOf(WatheItems.BAT) && target instanceof PlayerEntity playerTarget && this.getAttackCooldownProgress(0.5F) >= 1f) {
            if (self instanceof ServerPlayerEntity serverPlayer && playerTarget instanceof ServerPlayerEntity serverTarget) {
                GameRecordManager.recordItemHit(serverPlayer, serverPlayer.getMainHandStack(), GameConstants.DeathReasons.BAT, serverTarget, null);
            }
            GameFunctions.killPlayer(playerTarget, true, self, GameConstants.DeathReasons.BAT);
            self.getEntityWorld().playSound(self,
                    playerTarget.getX(), playerTarget.getEyeY(), playerTarget.getZ(),
                    WatheSounds.ITEM_BAT_HIT, SoundCategory.PLAYERS,
                    3f, 1f);
            return;
        }

        if (!GameFunctions.isPlayerAliveAndSurvival(self) || this.getMainHandStack().isOf(WatheItems.KNIFE)
                || (target instanceof PlayerEntity playerTarget && AllowPlayerPunching.EVENT.invoker().allowPunching(self, playerTarget))) {
            original.call(target);
        }
    }

    @Inject(method = "eatFood", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/HungerManager;eat(Lnet/minecraft/component/type/FoodComponent;)V", shift = At.Shift.AFTER))
    private void wathe$poisonedFoodEffect(@NotNull World world, ItemStack stack, FoodComponent foodComponent, CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClient) return;
        ItemStack replaySnapshot = stack.copy();
        boolean hasTrayEffect = TrayEffectUtils.hasTrayEffect(replaySnapshot);
        String poisoner = stack.getOrDefault(WatheDataComponentTypes.POISONER, null);
        if ((Object) this instanceof ServerPlayerEntity serverPlayer) {
            /**
             * 鸡尾酒虽然走的是 eatFood 流程，但回放里需要显示成“饮用鸡尾酒”，
             * 不能再额外记一条普通食物事件。
             *
             * 否则会出现两条记录：
             * 1. 这里的“食用了 某鸡尾酒”
             * 2. CocktailItem 里的“饮用了 [空气] / 某鸡尾酒”
             *
             * 因此普通食物回放要主动跳过鸡尾酒，只保留 CocktailItem 的专用饮用记录。
             * 下面的毒药处理仍然继续执行，这样带毒鸡尾酒依旧会正常进入中毒逻辑。
             */
            if (hasTrayEffect) {
                TrayEffectUtils.handleConsumeEffect(
                        serverPlayer,
                        replaySnapshot,
                        stack.getItem() instanceof CocktailItem ? "drink_cocktail" : "eat_food"
                );
            } else if (!(stack.getItem() instanceof CocktailItem)) {
                GameRecordManager.recordConsumeItem(
                        serverPlayer,
                        stack,
                        "eat_food",
                        poisoner != null,
                        poisoner == null ? null : UUID.fromString(poisoner),
                        null
                );
            }
        }
        if (!hasTrayEffect && poisoner != null) {
            int poisonTicks = PlayerPoisonComponent.KEY.get(this).poisonTicks;
            NbtCompound poisonData = new NbtCompound();
            poisonData.putString("item", net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString());
            if ((Object) this instanceof ServerPlayerEntity serverPlayer) {
                poisonData.putString("item_name", net.minecraft.text.Text.Serialization.toJsonString(stack.getName(), serverPlayer.getRegistryManager()));
            }
            if (poisonTicks == -1) {
                PlayerPoisonComponent.KEY.get(this).setDetailedPoisonTicks(
                        world.getRandom().nextBetween(PlayerPoisonComponent.clampTime.getLeft(), PlayerPoisonComponent.clampTime.getRight()),
                        UUID.fromString(poisoner),
                        GameConstants.DeathReasons.POISON,
                        poisonData
                );
            } else {
                PlayerPoisonComponent.KEY.get(this).setDetailedPoisonTicks(
                        MathHelper.clamp(poisonTicks - world.getRandom().nextBetween(100, 300), 0, PlayerPoisonComponent.clampTime.getRight()),
                        UUID.fromString(poisoner),
                        GameConstants.DeathReasons.POISON,
                        poisonData
                );
            }
        }
    }

    @Inject(method = "wakeUp(ZZ)V", at = @At("HEAD"))
    private void wathe$poisonSleep(boolean skipSleepTimer, boolean updateSleepingPlayers, CallbackInfo ci) {
        if (this.poisonSleepTask != null) {
            this.poisonSleepTask.cancel();
            this.poisonSleepTask = null;
        }
    }

    @Inject(method = "trySleep", at = @At("TAIL"))
    private void wathe$poisonSleepMessage(BlockPos pos, CallbackInfoReturnable<Either<PlayerEntity.SleepFailureReason, Unit>> cir) {
        PlayerEntity self = (PlayerEntity) (Object) (this);
        if (cir.getReturnValue().right().isPresent() && self instanceof ServerPlayerEntity serverPlayer) {
            if (this.poisonSleepTask != null) this.poisonSleepTask.cancel();

            this.poisonSleepTask = Scheduler.schedule(
                    () -> BedEffectRegistry.triggerBedEffect(serverPlayer),
                    40
            );
        }
    }

    @Inject(method = "canConsume(Z)Z", at = @At("HEAD"), cancellable = true)
    private void wathe$allowEatingRegardlessOfHunger(boolean ignoreHunger, @NotNull CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "eatFood", at = @At("HEAD"))
    private void wathe$eat(World world, ItemStack stack, FoodComponent foodComponent, @NotNull CallbackInfoReturnable<ItemStack> cir) {
        if (!(stack.getItem() instanceof CocktailItem)) {
            PlayerMoodComponent.KEY.get(this).eatFood();
        }
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void wathe$saveData(NbtCompound nbt, CallbackInfo ci) {
        nbt.putFloat("sprintingTicks", this.sprintingTicks);
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void wathe$readData(NbtCompound nbt, CallbackInfo ci) {
        this.sprintingTicks = nbt.getFloat("sprintingTicks");
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDay()Z"))
    private boolean wathe$cancelWakingUpPlayers(boolean original) {
        return false;
    }
}
