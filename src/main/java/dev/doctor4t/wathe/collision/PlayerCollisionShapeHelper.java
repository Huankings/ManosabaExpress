package dev.doctor4t.wathe.collision;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Wathe 玩家硬碰撞使用的内部碰撞箱工具。
 */
public final class PlayerCollisionShapeHelper {
    private static final double TRACKED_POSITION_EPSILON_SQUARED = 1.0E-8D;
    private static final double MEANINGFUL_PUSH_OVERLAP = 0.02D;

    private PlayerCollisionShapeHelper() {
    }

    public static Box getMovementCollisionBox(Entity entity) {
        Box currentBox = entity.getBoundingBox();
        if (!entity.getWorld().isClient() || entity.isLogicalSideForUpdatingMovement()) {
            return currentBox;
        }

        Vec3d trackedPos = new Vec3d(entity.getLerpTargetX(), entity.getLerpTargetY(), entity.getLerpTargetZ());
        Vec3d currentPos = entity.getPos();
        double offsetX = trackedPos.x - currentPos.x;
        double offsetY = trackedPos.y - currentPos.y;
        double offsetZ = trackedPos.z - currentPos.z;
        double offsetSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
        if (!Double.isFinite(offsetSquared) || offsetSquared < TRACKED_POSITION_EPSILON_SQUARED) {
            return currentBox;
        }

        /*
         * 客户端收到玩家 TP / 位置同步包后，远端玩家的 lerpTarget 会立刻变成服务端最新位置，
         * 但实体当前坐标会为了视觉平滑继续插值几 tick。SOLID 玩家墙如果仍按当前坐标做本地预测，
         * 玩家就可能从某些角度先被客户端放进墙内，再被服务端真实碰撞拉回。
         *
         * 因此这里仅对“客户端上的远端网络实体”把碰撞箱平移到 lerpTarget：
         * - 不改变渲染坐标，画面仍保持原版平滑；
         * - 不影响本地玩家自己的移动预测；
         * - 服务端仍使用真实 AABB 作为最终权威判定。
         */
        return currentBox.offset(offsetX, offsetY, offsetZ);
    }

    public static boolean hasMeaningfulPushOverlap(Entity self, Entity other) {
        Box selfBox = getMovementCollisionBox(self);
        Box otherBox = getMovementCollisionBox(other);
        double overlapX = Math.min(selfBox.maxX, otherBox.maxX) - Math.max(selfBox.minX, otherBox.minX);
        double overlapY = Math.min(selfBox.maxY, otherBox.maxY) - Math.max(selfBox.minY, otherBox.minY);
        double overlapZ = Math.min(selfBox.maxZ, otherBox.maxZ) - Math.max(selfBox.minZ, otherBox.minZ);

        /*
         * 原版 Box#intersects 对极小的浮点相交也会返回 true。TP 后远端玩家还在插值、服务端又在推挤解卡时，
         * 这种“只有一点点擦边”的相交会让 SOLID 同时出现实体墙裁剪和原版推挤速度，表现成挤压/转视角卡顿。
         *
         * SOLID 只需要在玩家真的被塞进彼此身体时保留原版轻微推挤用于解卡；
         * 正常贴边或擦角的情况则应像撞方块一样只走移动裁剪，不再额外写速度。
         */
        return overlapY > 1.0E-7D
                && overlapX > MEANINGFUL_PUSH_OVERLAP
                && overlapZ > MEANINGFUL_PUSH_OVERLAP;
    }
}
