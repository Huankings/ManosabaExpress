package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.combat.WeaponTargetingApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.client.particle.HandParticle;
import dev.doctor4t.wathe.client.render.WatheRenderLayers;
import dev.doctor4t.wathe.api.client.tooltip.ItemTooltipApi;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.util.GunShootPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DerringerItem extends RevolverItem {
    public DerringerItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(@NotNull World world, @NotNull PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        boolean used = stack.getOrDefault(WatheDataComponentTypes.USED, false);

        if (world.isClient) {
            HitResult collision = getGunAttackTarget(user, stack);
            if (collision instanceof EntityHitResult entityHitResult) {
                Entity target = entityHitResult.getEntity();
                ClientPlayNetworking.send(new GunShootPayload(target.getId()));
            } else {
                ClientPlayNetworking.send(new GunShootPayload(-1));
            }
            if (!used) {
                user.setPitch(user.getPitch() - 4);
                spawnHandParticle();
            }
        }
        return TypedActionResult.consume(stack);
    }

    public static void spawnHandParticle() {
        HandParticle handParticle = new HandParticle()
                .setTexture(Wathe.id("textures/particle/gunshot.png"))
                .setPos(0.1f, 0.2f, -0.2f)
                .setMaxAge(3)
                .setSize(0.5f)
                .setVelocity(0f, 0f, 0f)
                .setLight(15, 15)
                .setAlpha(1f, 0.1f)
                .setRenderLayer(WatheRenderLayers::additive);
        WatheClient.handParticleManager.spawn(handParticle);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        Boolean used = stack.getOrDefault(WatheDataComponentTypes.USED, false);
        if (used) {
            tooltip.add(Text.translatable("tip.derringer.used").withColor(ItemTooltipApi.COOLDOWN_COLOR));
        }

        super.appendTooltip(stack, context, tooltip, type);
    }

    public static @Nullable HitResult getGunTarget(PlayerEntity user) {
        return getGunTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getGunTarget(PlayerEntity user, ItemStack stack) {
        /*
         * 德林加准心仍然只看 TARGET 语义。
         * 真实发包路径单独走 getGunAttackTarget，避免尸体伪装因为隐藏准心而顺带免疫伤害。
         */
        return WeaponTargetingApi.resolveVisibleGunTarget(user, stack, 7F);
    }

    public static @Nullable HitResult getGunAttackTarget(PlayerEntity user) {
        return getGunAttackTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getGunAttackTarget(PlayerEntity user, ItemStack stack) {
        HitResult visibleTarget = user.getMainHandStack() == stack ? getGunTarget(user) : getGunTarget(user, stack);
        if (visibleTarget instanceof EntityHitResult entityHitResult) {
            /*
             * 继续兼容旧扩展对德林加准心目标的窄修正。
             * 只有当可见目标本身允许 ATTACK 时，客户端才把它作为真实开火目标发给服务端。
             */
            return TargetVisibilityApi.canAttackEntity(user, entityHitResult.getEntity()) ? visibleTarget : null;
        }
        /*
         * 非实体 HitResult 只表示准心显示没有玩家目标，不能直接结束真实开火目标选择。
         * 德林加射程短，伪装尸体常常贴着地面或墙边；如果这里把 MISS/方块命中直接返回，
         * 客户端仍会向服务端发送 -1，看起来就像 ATTACK 语义完全没有生效。
         */
        return WeaponTargetingApi.resolveAttackableGunTarget(user, stack, 7F);
    }
}
