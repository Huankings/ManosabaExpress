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
import net.minecraft.util.math.MathHelper;
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
    private static final double DISTANCE_EPSILON = 1.0E-4D;

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
     * 判断两个目标解析结果是否命中了同一个实体。
     *
     * <p>准心 provider 可以分别解析 VISIBLE 与 ATTACK 目标，再用本方法确认两条语义
     * 最终指向同一实体。这样“客户端看得见但当前规则禁止攻击”的目标不会继续点亮
     * 可命中准心；本方法本身不代替服务端权威校验。</p>
     */
    public static boolean isSameEntityTarget(@Nullable HitResult first, @Nullable HitResult second) {
        return first instanceof EntityHitResult firstEntity
                && second instanceof EntityHitResult secondEntity
                && firstEntity.getEntity().getId() == secondEntity.getEntity().getId();
    }

    /**
     * 按客户端射线相同的几何口径校验实体距离，边界值视为命中。
     *
     * @see #isWithinTargetingRange(Entity, Entity, double, boolean)
     */
    public static boolean isWithinTargetingRange(@NotNull Entity source,
                                                 @NotNull Entity target,
                                                 double range) {
        return isWithinTargetingRange(source, target, range, true);
    }

    /**
     * 按“攻击者眼睛到目标可瞄准碰撞箱最近点”校验距离。
     *
     * <p>{@link ProjectileUtil#getCollision(Entity, java.util.function.Predicate, double)} 会让射线
     * 命中目标碰撞箱表面；如果服务端随后改用 {@link Entity#distanceTo(Entity)} 比较两个实体
     * 的位置点，目标中心位于极限距离外时就会出现准心已变化、攻击却被拒绝的假反馈。
     * 这里把目标碰撞箱按 targeting margin 扩张，再取眼睛到盒子的最近点，使客户端准心、
     * C2S 发包与服务端验收共享同一种距离定义。</p>
     *
     * <p>本方法只校验距离，不校验视线、墙体、主手物品、冷却、存活状态或 ATTACK 权限；
     * 包接收器和扩展 handler 仍必须分别完成这些权威检查。</p>
     */
    public static boolean isWithinTargetingRange(@NotNull Entity source,
                                                 @NotNull Entity target,
                                                 double range,
                                                 boolean inclusive) {
        if (range < 0.0D) {
            return false;
        }

        Vec3d eyePos = source.getEyePos();
        Box targetBox = target.getBoundingBox().expand(target.getTargetingMargin());
        double closestX = MathHelper.clamp(eyePos.x, targetBox.minX, targetBox.maxX);
        double closestY = MathHelper.clamp(eyePos.y, targetBox.minY, targetBox.maxY);
        double closestZ = MathHelper.clamp(eyePos.z, targetBox.minZ, targetBox.maxZ);
        double distanceSquared = eyePos.squaredDistanceTo(closestX, closestY, closestZ);
        double rangeSquared = range * range;
        return inclusive
                ? distanceSquared <= rangeSquared + DISTANCE_EPSILON
                : distanceSquared < rangeSquared;
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
            if (distanceSquared <= nearestDistanceSquared + DISTANCE_EPSILON) {
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
