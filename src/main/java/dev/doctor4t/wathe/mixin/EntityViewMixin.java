package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.api.collision.PlayerCollisionApi;
import dev.doctor4t.wathe.api.collision.PlayerCollisionMode;
import dev.doctor4t.wathe.collision.PlayerCollisionShapeHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EntityView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 在移动碰撞列表生成层接入玩家碰撞 API。
 *
 * <p>{@link Entity#collidesWith(Entity)} 本身会被很多扩展 mixin 包裹，优先级稍有不同就可能出现
 * 服务端认为能挡住、客户端预测却放行的情况。这里在 Minecraft 真正把实体转换成移动碰撞
 * {@link VoxelShape} 前统一询问 {@link PlayerCollisionApi}，让 Wathe 默认硬阻挡、FEATHER 原版推挤、
 * 灵术师空气壳都走同一套结果。</p>
 */
@Mixin(EntityView.class)
public interface EntityViewMixin {
    @Shadow
    List<Entity> getOtherEntities(Entity except, Box box, Predicate<? super Entity> predicate);

    @Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true)
    private void wathe$applyPlayerCollisionApi(Entity entity,
                                               Box box,
                                               CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (!(entity instanceof PlayerEntity selfPlayer)) {
            return;
        }
        if (box.getAverageSideLength() < 1.0E-7D) {
            cir.setReturnValue(List.of());
            return;
        }

        Box searchBox = box.expand(1.0E-7D);
        Predicate<Entity> predicate = EntityPredicates.EXCEPT_SPECTATOR.and(candidate -> {
            if (candidate instanceof PlayerEntity otherPlayer) {
                PlayerCollisionMode mode = PlayerCollisionApi.resolve(selfPlayer, otherPlayer);
                if (mode != PlayerCollisionMode.PASS) {
                    return mode.blocksMovement();
                }
            }
            return entity.collidesWith(candidate);
        });

        List<Entity> candidates = this.getOtherEntities(entity, searchBox, predicate);
        List<VoxelShape> shapes = new ArrayList<>(candidates.size());
        Set<PlayerEntity> includedPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity candidate : candidates) {
            if (candidate instanceof PlayerEntity otherPlayer
                    && PlayerCollisionApi.blocksMovement(selfPlayer, otherPlayer)) {
                includedPlayers.add(otherPlayer);
                shapes.add(VoxelShapes.cuboid(PlayerCollisionShapeHelper.getMovementCollisionBox(otherPlayer)));
            } else {
                shapes.add(VoxelShapes.cuboid(candidate.getBoundingBox()));
            }
        }

        /*
         * TP 或强制改坐标后的几个客户端 tick 内，远端玩家的当前 AABB 会为了视觉平滑继续插值，
         * 但服务端已经把这个玩家放在 lerpTarget。原版空间查询只看当前 AABB，可能漏掉“服务端已经在身边、
         * 客户端外观还没完全追上”的玩家墙，于是本地预测会在少数角度放行，随后被服务端拉回。
         *
         * 这里额外遍历玩家列表，用 PlayerCollisionShapeHelper 取 lerpTarget 坐标上的 SOLID 碰撞箱补漏。
         * 已经被原空间查询命中的玩家不重复加入，避免列表无意义膨胀。
         */
        for (PlayerEntity otherPlayer : selfPlayer.getWorld().getPlayers()) {
            if (otherPlayer == selfPlayer
                    || includedPlayers.contains(otherPlayer)
                    || !EntityPredicates.EXCEPT_SPECTATOR.test(otherPlayer)) {
                continue;
            }
            if (!PlayerCollisionApi.blocksMovement(selfPlayer, otherPlayer)) {
                continue;
            }

            Box collisionBox = PlayerCollisionShapeHelper.getMovementCollisionBox(otherPlayer);
            if (collisionBox.intersects(searchBox)) {
                shapes.add(VoxelShapes.cuboid(collisionBox));
            }
        }

        cir.setReturnValue(List.copyOf(shapes));
    }
}
