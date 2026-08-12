package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.combat.WeaponTargetingApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.client.particle.HandParticle;
import dev.doctor4t.wathe.client.render.WatheRenderLayers;
import dev.doctor4t.wathe.util.GunShootPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class RevolverItem extends Item {
    public RevolverItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(@NotNull World world, @NotNull PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) {
            HitResult collision = getGunAttackTarget(user, stack);
            if (collision instanceof EntityHitResult entityHitResult) {
                Entity target = entityHitResult.getEntity();
                ClientPlayNetworking.send(new GunShootPayload(target.getId()));
            } else {
                ClientPlayNetworking.send(new GunShootPayload(-1));
            }
            user.setPitch(user.getPitch() - 4);
            spawnHandParticle();
        }
        return TypedActionResult.consume(stack);
    }

    public static void spawnHandParticle() {
        HandParticle handParticle = new HandParticle()
                .setTexture(Wathe.id("textures/particle/gunshot.png"))
                .setPos(0.1f, 0.275f, -0.2f)
                .setMaxAge(3)
                .setSize(0.5f)
                .setVelocity(0f, 0f, 0f)
                .setLight(15, 15)
                .setAlpha(1f, 0.1f)
                .setRenderLayer(WatheRenderLayers::additive);
        WatheClient.handParticleManager.spawn(handParticle);
    }

    public static @Nullable HitResult getGunTarget(PlayerEntity user) {
        return getGunTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getGunTarget(PlayerEntity user, ItemStack stack) {
        /*
         * 这个方法保留“准心 / HUD 目标”的旧语义：
         * 只要某个扩展通过 TargetVisibilityApi 拒绝 TARGET，左轮准心就不能变成命中态。
         * 真正开火发包请走 getGunAttackTarget，不要再把准心隐藏误当成攻击免疫。
         */
        return WeaponTargetingApi.resolveVisibleGunTarget(user, stack, 20F);
    }

    public static @Nullable HitResult getGunAttackTarget(PlayerEntity user) {
        return getGunAttackTarget(user, user.getMainHandStack());
    }

    public static @Nullable HitResult getGunAttackTarget(PlayerEntity user, ItemStack stack) {
        HitResult visibleTarget = user.getMainHandStack() == stack ? getGunTarget(user) : getGunTarget(user, stack);
        if (visibleTarget instanceof EntityHitResult entityHitResult) {
            /*
             * 先尊重旧扩展对 getGunTarget 的窄 mixin / 覆写：魔术师播放体、胆小鬼偏移射线等
             * 都属于“客户端射线形状”而不是尸体伪装这类 TargetVisibility 例外。
             * 如果这个可见目标本身不允许被攻击，则不要继续发包。
             */
            return TargetVisibilityApi.canAttackEntity(user, entityHitResult.getEntity()) ? visibleTarget : null;
        }
        /*
         * getGunTarget 可能返回 MISS 或方块命中，而不是 Java null。
         * 这些非实体结果只说明“准心显示没有玩家目标”，不能拦住下面的 ATTACK 射线；
         * 否则亡语杀手尸体伪装站在地面/墙体前时，客户端会因为先拿到非实体 HitResult 而继续发送 -1。
         *
         * ATTACK 射线内部仍会比较方块阻挡距离，所以这里不会让左轮穿墙命中。
         * 这样亡语杀手尸体伪装不会点亮准心，但仍能被真实枪击命中。
         */
        return WeaponTargetingApi.resolveAttackableGunTarget(user, stack, 20F);
    }
}
