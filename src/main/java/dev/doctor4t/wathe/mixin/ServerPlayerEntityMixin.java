package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.util.BatAttackCooldownPreserver;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin implements BatAttackCooldownPreserver {
    @Unique
    private int wathe$preserveBatSwingCooldownUntilAge = -1;
    @Unique
    private @Nullable Hand wathe$preserveBatSwingCooldownHand;

    @WrapOperation(method = "trySleep", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;sendMessage(Lnet/minecraft/text/Text;Z)V"))
    public void wathe$disableSleepMessage(ServerPlayerEntity instance, Text message, boolean overlay, Operation<Void> original) {
    }

    @WrapOperation(method = "trySleep", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;setSpawnPoint(Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/util/math/BlockPos;FZZ)V"))
    public void wathe$disableSetSpawnpoint(ServerPlayerEntity instance, RegistryKey<World> dimension, @Nullable BlockPos pos, float angle, boolean forced, boolean sendMessage, Operation<Void> original) {
    }

    @ModifyExpressionValue(method = "trySleep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDay()Z"))
    public boolean wathe$allowSleepingAtAnyTime(boolean original) {
        return false;
    }

    @Inject(method = "changeGameMode", at = @At("HEAD"))
    private void wathe$clearAliveOverrideForNormalGameModeChanges(GameMode gameMode, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        /*
         * 普通原版切到旁观 / 创造时，必须撤销 Wathe 的“特殊模式仍存活”授权。
         *
         * 只有通过 PlayerLifeStateApi.changeGameModeAsGameplayAlive 发起的切模式才会保留该授权。
         * 这样 OP 自己执行 /gamemode spectator 或 /gamemode creative 时，仍会回到非存活状态。
         */
        if (PlayerLifeStateApi.isNonSurvivalMode(gameMode)
                && !PlayerLifeStateApi.isGameplayAliveGameModeChangeAllowed(self)) {
            PlayerLifeStateApi.clearAliveOverride(self);
        }
    }

    @Override
    public void wathe$preserveNextBatInteractionSwing(Hand hand) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (!PsychoModeApi.isMeleeKillWeapon(self, self.getStackInHand(hand))) {
            return;
        }

        /*
         * 右键交互成功后客户端还会发一次挥手包；原版服务端处理这个挥手包时会清空攻击冷却。
         * 球棒击杀依赖满蓄力，所以给“紧接着到来的这一次挥手”一个短暂豁免窗口。
         */
        this.wathe$preserveBatSwingCooldownUntilAge = self.age + 2;
        this.wathe$preserveBatSwingCooldownHand = hand;
    }

    @WrapOperation(method = "swingHand(Lnet/minecraft/util/Hand;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;resetLastAttackedTicks()V"))
    private void wathe$keepBatCooldownAfterRightClickSwing(ServerPlayerEntity instance, Operation<Void> original, @Local(argsOnly = true) Hand hand) {
        if (this.wathe$shouldPreserveBatSwingCooldown(instance, hand)) {
            return;
        }

        original.call(instance);
    }

    @Unique
    private boolean wathe$shouldPreserveBatSwingCooldown(ServerPlayerEntity player, Hand hand) {
        if (this.wathe$preserveBatSwingCooldownHand == null || player.age > this.wathe$preserveBatSwingCooldownUntilAge) {
            this.wathe$clearBatSwingCooldownPreservation();
            return false;
        }

        boolean shouldPreserve = this.wathe$preserveBatSwingCooldownHand == hand && PsychoModeApi.isMeleeKillWeapon(player, player.getStackInHand(hand));
        this.wathe$clearBatSwingCooldownPreservation();
        return shouldPreserve;
    }

    @Unique
    private void wathe$clearBatSwingCooldownPreservation() {
        this.wathe$preserveBatSwingCooldownUntilAge = -1;
        this.wathe$preserveBatSwingCooldownHand = null;
    }

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void wathe$preventDroppingLockedPsychoItem(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        /*
         * 疯魔 profile 授予的锁定物品应该由 profile 结束流程回收。
         * 如果允许玩家中途丢出，后续就会出现“皮肤/护盾还在，但核心武器已经落地”的半状态。
         */
        if (PsychoModeApi.shouldPreventDrop(self, self.getMainHandStack())) {
            cir.setReturnValue(false);
        }
    }
}
