package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.util.BatAttackCooldownPreserver;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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

    @Override
    public void wathe$preserveNextBatInteractionSwing(Hand hand) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (!self.getStackInHand(hand).isOf(WatheItems.BAT)) {
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

        boolean shouldPreserve = this.wathe$preserveBatSwingCooldownHand == hand && player.getStackInHand(hand).isOf(WatheItems.BAT);
        this.wathe$clearBatSwingCooldownPreservation();
        return shouldPreserve;
    }

    @Unique
    private void wathe$clearBatSwingCooldownPreservation() {
        this.wathe$preserveBatSwingCooldownUntilAge = -1;
        this.wathe$preserveBatSwingCooldownHand = null;
    }
}
