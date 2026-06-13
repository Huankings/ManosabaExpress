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
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerPoisonComponent;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.MovementConfig;
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
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import org.spongepowered.asm.mixin.injection.Redirect;
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
    private boolean sprintingTicksResetForCurrentRound;
    @Unique
    private Scheduler.ScheduledTask poisonSleepTask;

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }


    @ModifyReturnValue(method = "getMovementSpeed", at = @At("RETURN"))
    public float wathe$overrideMovementSpeed(float original) {
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(this.getWorld());
        if (gameComponent != null && gameComponent.isRunning() && GameFunctions.isPlayerAliveAndSurvival((PlayerEntity) (Object) this)) {
            MovementConfig movement = MapEnhancementsWorldComponent.KEY.get(this.getWorld()).getMovementConfig();

            /*
             * 原版返回值里已经包含速度/缓慢等状态效果。
             * 这里只替换 Wathe 的基础走路/疾跑速度，再把状态效果乘数补回去。
             */
            float baseAttributeValue = (float) this.getAttributeBaseValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            float vanillaExpectedSpeed = this.isSprinting() ? baseAttributeValue * 1.3f : baseAttributeValue;
            float effectMultiplier = vanillaExpectedSpeed > 0 ? original / vanillaExpectedSpeed : 1.0f;
            float watheBaseSpeed = this.isSprinting() ? 0.1f * movement.sprintSpeedMultiplier() : 0.07f * movement.walkSpeedMultiplier();
            return watheBaseSpeed * effectMultiplier;
        } else {
            return original;
        }
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    public void wathe$limitSprint(CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(this.getWorld());
        boolean isRunningGame = gameComponent != null && gameComponent.isRunning();
        if (!isRunningGame) {
            /*
             * STARTING / INACTIVE 阶段会经过这里，把标记放回 false。
             * 下一局真正进入 ACTIVE 并拿到角色后，有限体力玩家就会再次从 0 开始恢复体力。
             */
            this.sprintingTicksResetForCurrentRound = false;
            return;
        }
        /*
         * 调试时可能在同一局内临时切到创造 / 旁观再切回生存 / 冒险。
         * 这种情况只暂停体力逻辑，不能复位本局清零标记，否则切回来会被当成开局再次清空体力。
         */
        if (!GameFunctions.isPlayerAliveAndSurvival(self)) {
            return;
        }

        Role role = gameComponent.getRole(self);
        if (role == null) {
            return;
        }

        if (!this.sprintingTicksResetForCurrentRound) {
            /*
             * 当前体力值存在玩家 NBT 里，原先不会随每局游戏初始化而清空，
             * 导致上一局结束时剩多少体力，下一局开局就继承多少体力。
             * 这里在每局第一次拿到角色时清空有限体力角色；无限体力角色（-1）不需要处理。
             */
            if (role.getMaxSprintTime() >= 0) {
                this.sprintingTicks = 0;
                this.setSprinting(false);
            }
            this.sprintingTicksResetForCurrentRound = true;
            return;
        }

        if (role.getMaxSprintTime() >= 0) {
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

    @WrapMethod(method = "attack")
    public void attack(Entity target, Operation<Void> original) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        ItemStack mainHandStack = this.getMainHandStack();

        if (mainHandStack.isOf(WatheItems.KNIFE) && !(target instanceof PlayerEntity)) {
            // 匕首的左键用途只应该是推玩家，命中画、展示框、盔甲架等装饰实体时直接忽略，避免原版攻击把它们打掉。
            return;
        }

        if (mainHandStack.isOf(WatheItems.BAT) && target instanceof PlayerEntity playerTarget && this.getAttackCooldownProgress(0.5F) >= 1f) {
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

        if (!GameFunctions.isPlayerAliveAndSurvival(self) || mainHandStack.isOf(WatheItems.KNIFE)
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

    @Inject(method = "eatFood", at = @At("RETURN"), cancellable = true)
    private void wathe$clearBowlRemainder(World world, ItemStack stack, FoodComponent foodComponent, @NotNull CallbackInfoReturnable<ItemStack> cir) {
        if (this.wathe$shouldCleanFoodContainer(world) && cir.getReturnValue().isOf(Items.BOWL)) {
            // 游戏进行中，存活玩家吃完碗装食物时直接清掉碗，避免背包里堆积空碗。
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Redirect(method = "eatFood", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;insertStack(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean wathe$skipBowlInsert(PlayerInventory inventory, ItemStack remainder) {
        if (this.wathe$shouldCleanFoodContainer(this.getWorld()) && remainder.isOf(Items.BOWL)) {
            // 多份堆叠等特殊情况下，原版会尝试把空碗插回背包；这里同样拦掉。
            return true;
        }

        return inventory.insertStack(remainder);
    }

    @Unique
    private boolean wathe$shouldCleanFoodContainer(World world) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(world);
        return gameComponent != null && gameComponent.isRunning() && GameFunctions.isPlayerAliveAndSurvival(self);
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void wathe$saveData(NbtCompound nbt, CallbackInfo ci) {
        nbt.putFloat("sprintingTicks", this.sprintingTicks);
        nbt.putBoolean("sprintingTicksResetForCurrentRound", this.sprintingTicksResetForCurrentRound);
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void wathe$readData(NbtCompound nbt, CallbackInfo ci) {
        this.sprintingTicks = nbt.getFloat("sprintingTicks");
        this.sprintingTicksResetForCurrentRound = nbt.getBoolean("sprintingTicksResetForCurrentRound");
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDay()Z"))
    private boolean wathe$cancelWakingUpPlayers(boolean original) {
        return false;
    }

    
    // Layer 1: 游戏进行中跳过 applyDamage（扣血），但让 damage() 正常返回 true
    // 这样击退、受伤动画、hurtTime 等副作用都保留，只是不实际扣血
    @Inject(method = "applyDamage", at = @At("HEAD"), cancellable = true)
    private void wathe$cancelApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (GameFunctions.isPlayerAliveAndSurvival(self)) {
            ci.cancel();
        }
    }
}
