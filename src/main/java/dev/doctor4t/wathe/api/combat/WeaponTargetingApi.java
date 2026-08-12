package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 武器客户端射线目标的公共工具。
 *
 * <p>这里把“准心显示目标”和“真实攻击目标”拆成两条明确入口：
 * 准心 / HUD / 名字提示只应使用 TARGET 语义，真实发包 / 命中结算才使用 ATTACK 语义。
 * 这样扩展职业如果让玩家隐藏准心身份，例如伪装成尸体，也不会顺手获得不合理的无敌窗口。</p>
 */
public final class WeaponTargetingApi {
    private WeaponTargetingApi() {
    }

    /**
     * 获取准心显示用的局内存活玩家目标。
     *
     * <p>扩展的准心图标、玩家名提示和“是否高亮为可锁定”反馈应调用这个方法。
     * 它会尊重 {@link TargetVisibilityApi#canTargetPlayer(PlayerEntity, PlayerEntity)}，
     * 因此不会暴露那些只隐藏 TARGET 语义的伪装状态。</p>
     */
    public static @Nullable EntityHitResult getVisibleAlivePlayerTarget(@NotNull PlayerEntity user, double range) {
        return getAlivePlayerTarget(user, range, false);
    }

    /**
     * 获取真实攻击发包用的局内存活玩家目标。
     *
     * <p>扩展武器客户端准备发送 C2S 命中包时应调用这个方法。
     * 服务端仍必须再次校验距离、手持物、冷却和 {@link TargetVisibilityApi#canAttackPlayer(PlayerEntity, PlayerEntity)}；
     * 客户端这里只负责避免“准心隐藏”和“真实攻击”共用同一条错误判定。</p>
     */
    public static @Nullable EntityHitResult getAttackableAlivePlayerTarget(@NotNull PlayerEntity user, double range) {
        return getAlivePlayerTarget(user, range, true);
    }

    /**
     * 解析枪械准心显示用目标。
     *
     * <p>枪械还需要经过 {@link GunShotApi#registerTargetRule} 注册的客户端目标覆写链，
     * 例如假左轮强制 miss、扩展职业修改射线来源等。解析完成后仍用 TARGET 语义做最后兜底，
     * 避免覆写规则意外把被准心隐藏的玩家重新暴露出来。</p>
     */
    public static @Nullable HitResult resolveVisibleGunTarget(@NotNull PlayerEntity user,
                                                              @NotNull ItemStack stack,
                                                              double range) {
        return resolveGunTarget(user, stack, range, false);
    }

    /**
     * 解析枪械真实攻击发包用目标。
     *
     * <p>这条路径同样会经过枪械目标覆写链，但最后使用 ATTACK 语义验收。
     * 因此假左轮等“本次必须 miss”的扩展规则仍然生效，而尸体伪装这类只拒绝 TARGET 的目标
     * 仍可以被正常发包攻击。</p>
     */
    public static @Nullable HitResult resolveAttackableGunTarget(@NotNull PlayerEntity user,
                                                                 @NotNull ItemStack stack,
                                                                 double range) {
        return resolveGunTarget(user, stack, range, true);
    }

    private static @Nullable EntityHitResult getAlivePlayerTarget(@NotNull PlayerEntity user, double range, boolean attack) {
        if (attack) {
            /*
             * ATTACK 语义不能继续走 ProjectileUtil.getCollision。
             *
             * Wathe 会在客户端 LivingEntity#canHit 里接入 TARGET 语义，用来隐藏准心、名字和命中提示。
             * 亡语杀手伪装尸体正是依赖这个入口：它拒绝 TARGET，但仍允许 ATTACK。
             *
             * ProjectileUtil.getCollision 在进入 predicate 前还会参考实体自己的 canHit()，因此会把
             * “TARGET 被隐藏、ATTACK 允许”的玩家提前过滤掉。这里手写一条只服务真实攻击发包的射线，
             * 直接询问 canAttackPlayer，并且仍比较方块命中距离，避免枪/刀/球棒补包变成穿墙攻击。
             */
            return getAttackableAlivePlayerTargetIgnoringTargetCanHit(user, range);
        }

        HitResult hitResult = ProjectileUtil.getCollision(
                user,
                entity -> entity instanceof PlayerEntity player
                        && GameFunctions.isPlayerAliveAndSurvival(player)
                        && canUsePlayer(user, player, attack),
                range
        );
        return hitResult instanceof EntityHitResult entityHitResult ? entityHitResult : null;
    }

    private static @Nullable HitResult resolveGunTarget(@NotNull PlayerEntity user,
                                                        @NotNull ItemStack stack,
                                                        double range,
                                                        boolean attack) {
        HitResult defaultTarget = attack
                ? getAttackableAlivePlayerTargetIgnoringTargetCanHit(user, range)
                : ProjectileUtil.getCollision(
                user,
                entity -> entity instanceof PlayerEntity player
                        && GameFunctions.isPlayerAliveAndSurvival(player)
                        && canUsePlayer(user, player, false),
                range
        );
        HitResult resolvedTarget = GunShotApi.resolveTarget(new GunTargetContext(user, stack, range, defaultTarget));
        if (resolvedTarget instanceof EntityHitResult entityHitResult
                && !canUseEntity(user, entityHitResult.getEntity(), attack)) {
            return null;
        }
        return resolvedTarget;
    }

    private static @Nullable EntityHitResult getAttackableAlivePlayerTargetIgnoringTargetCanHit(
            @NotNull PlayerEntity user,
            double range
    ) {
        Vec3d eyePos = user.getEyePos();
        Vec3d look = user.getRotationVec(1.0F).normalize();
        Vec3d ray = look.multiply(range);
        Vec3d endPos = eyePos.add(ray);
        Box searchBox = user.getBoundingBox().stretch(ray).expand(1.0D);

        /*
         * 先算出方块阻挡距离，再找实体命中点。
         * 这样我们虽然绕过了 LivingEntity#canHit 的 TARGET 过滤，但没有绕过墙、门、地板等实体前方阻挡。
         */
        double nearestDistanceSquared = range * range;
        HitResult blockHit = user.raycast(range, 1.0F, false);
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            nearestDistanceSquared = Math.min(nearestDistanceSquared, eyePos.squaredDistanceTo(blockHit.getPos()));
        }

        Entity bestEntity = null;
        Vec3d bestHitPos = null;
        for (Entity entity : user.getWorld().getOtherEntities(user, searchBox, entity ->
                entity instanceof PlayerEntity target
                        && entity != user
                        && !entity.isSpectator()
                        && GameFunctions.isPlayerAliveAndSurvival(target)
                        && TargetVisibilityApi.canAttackPlayer(user, target))) {
            Box targetBox = entity.getBoundingBox().expand(entity.getTargetingMargin());
            Vec3d hitPos = targetBox.contains(eyePos)
                    ? eyePos
                    : targetBox.raycast(eyePos, endPos).orElse(null);
            if (hitPos == null) {
                continue;
            }

            double distanceSquared = eyePos.squaredDistanceTo(hitPos);
            if (distanceSquared <= nearestDistanceSquared + 1.0E-4D) {
                nearestDistanceSquared = distanceSquared;
                bestEntity = entity;
                bestHitPos = hitPos;
            }
        }

        return bestEntity == null ? null : new EntityHitResult(bestEntity, bestHitPos);
    }

    private static boolean canUsePlayer(@NotNull PlayerEntity user, @NotNull PlayerEntity target, boolean attack) {
        return attack
                ? TargetVisibilityApi.canAttackPlayer(user, target)
                : TargetVisibilityApi.canTargetPlayer(user, target);
    }

    private static boolean canUseEntity(@NotNull PlayerEntity user, @NotNull net.minecraft.entity.Entity entity, boolean attack) {
        return attack
                ? TargetVisibilityApi.canAttackEntity(user, entity)
                : TargetVisibilityApi.canTargetEntity(user, entity);
    }
}
