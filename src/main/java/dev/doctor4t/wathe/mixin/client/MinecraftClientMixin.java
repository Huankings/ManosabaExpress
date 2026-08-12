package dev.doctor4t.wathe.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.api.combat.WeaponTargetingApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Shadow
    @Nullable
    public ClientPlayerEntity player;
    @Shadow
    @Nullable
    public ClientPlayerInteractionManager interactionManager;

    @ModifyReturnValue(method = "hasOutline", at = @At("RETURN"))
    public boolean wathe$hasInstinctOutline(boolean original, @Local(argsOnly = true) Entity entity) {
        if (WatheClient.getInstinctHighlight(entity) != -1) return true;
        return original;
    }

    @WrapWithCondition(method = "doItemUse",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;resetEquipProgress(Lnet/minecraft/util/Hand;)V"
            ))
    private boolean wathe$cancelRevolverUpdateAnimation(HeldItemRenderer instance, Hand hand) {
        return !MinecraftClient.getInstance().player.getStackInHand(hand).isIn(WatheItemTags.GUNS);
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void wathe$attackTargetVisibilityHiddenMeleeTarget(CallbackInfoReturnable<Boolean> cir) {
        ClientPlayerEntity localPlayer = this.player;
        if (localPlayer == null || this.interactionManager == null) {
            return;
        }

        ItemStack stack = localPlayer.getMainHandStack();
        if (!PsychoModeApi.isMeleeKillWeapon(localPlayer, stack)) {
            return;
        }

        if (MinecraftClient.getInstance().crosshairTarget instanceof EntityHitResult result
                && TargetVisibilityApi.canTargetEntity(localPlayer, result.getEntity())) {
            /*
             * 已经有正常准心实体时，必须完整交还给原版 doAttack。
             * 隐藏目标补包只服务“ATTACK 允许但 TARGET 故意隐藏”的尸体伪装场景；
             * 如果这里抢了普通玩家 / 普通实体的左键流程，球棒准心和原版攻击手感都会被破坏。
             */
            return;
        }

        /*
         * 原版左键攻击只会攻击 client.crosshairTarget。
         * 尸体伪装这类状态会故意拒绝 TARGET，让准心、名字和命中提示都不暴露“尸体其实是玩家”；
         * 但真实近战击杀不能因此失效，否则伪装者会获得一段不合理的无敌时间。
         *
         * 所以这里只在“ATTACK 允许、TARGET 拒绝”的窄场景里补发一次攻击包。
         * 如果准心已经锁到了正常玩家，仍交给原版 doAttack 和后续服务端校验处理。
         */
        EntityHitResult hiddenTarget = WeaponTargetingApi.getAttackableAlivePlayerTarget(
                localPlayer,
                localPlayer.getEntityInteractionRange()
        );
        if (hiddenTarget == null || !(hiddenTarget.getEntity() instanceof PlayerEntity target)) {
            return;
        }
        if (TargetVisibilityApi.canTargetPlayer(localPlayer, target)
                || !wathe$isEntityHitBeforeBlockingBlock(localPlayer, hiddenTarget, localPlayer.getEntityInteractionRange())) {
            return;
        }

        this.interactionManager.attackEntity(localPlayer, target);
        localPlayer.swingHand(Hand.MAIN_HAND);
        cir.setReturnValue(true);
    }

    @Unique
    private static boolean wathe$isEntityHitBeforeBlockingBlock(ClientPlayerEntity player, EntityHitResult entityHit, double range) {
        HitResult blockHit = player.raycast(range, 1.0F, false);
        if (blockHit == null || blockHit.getType() == HitResult.Type.MISS) {
            return true;
        }

        /*
         * 近战补包不能穿墙。
         * 攻击射线只负责找“被 TARGET 隐藏但 ATTACK 允许”的玩家；这里再用原版方块 raycast
         * 比较谁更近，确保门、墙、地板等实体前方阻挡仍然能拦住球棒。
         */
        double entityDistanceSquared = player.getEyePos().squaredDistanceTo(entityHit.getPos());
        double blockDistanceSquared = player.getEyePos().squaredDistanceTo(blockHit.getPos());
        return entityDistanceSquared <= blockDistanceSquared + 1.0E-4D;
    }

    @WrapOperation(method = "handleInputEvents", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/PlayerInventory;selectedSlot:I"))
    private void wathe$invalid(@NotNull PlayerInventory instance, int value, Operation<Void> original) {
        int oldSlot = instance.selectedSlot;
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(instance.player);
        if (component.isPsychoActive()
                && component.getProfile().lockHotbar()
                && PsychoModeApi.isLockedItem(instance.player, instance.getStack(oldSlot))
                && !PsychoModeApi.isLockedItem(instance.player, instance.getStack(value))
        ) return;
        original.call(instance, value);
    }
}
