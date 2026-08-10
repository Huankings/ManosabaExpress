package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.doctor4t.wathe.api.collision.PlayerCollisionApi;
import dev.doctor4t.wathe.api.collision.PlayerCollisionMode;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.collision.PlayerCollisionShapeHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Entity.class)
public class EntityMixin {
    @Shadow
    private World world;

    /**
     * 统一接管 Wathe 额外添加的“玩家实体碰撞体积”。
     *
     * <p>只有在以下条件同时满足时，才会强制返回 true：
     * 1. 对局正在运行；
     * 2. 世界配置里没有关闭玩家碰撞体积；
     * 3. 本局已经过了“开局无碰撞”保护秒数；
     * 4. self 与 other 都是 Wathe 定义下的“局内存活玩家”。
     *
     * <p>当仍处于开局保护秒数内时，会对存活玩家之间显式返回 false，
     * 确保这段时间内没有 Wathe 强制出来的碰撞体积。
     */
    @WrapMethod(method = "collidesWith")
    protected boolean wathe$solid(Entity other, Operation<Boolean> original) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PlayerEntity selfPlayer && other instanceof PlayerEntity otherPlayer) {
            PlayerCollisionMode mode = PlayerCollisionApi.resolve(selfPlayer, otherPlayer);
            if (mode != PlayerCollisionMode.PASS) {
                return mode.blocksMovement();
            }
        }
        return original.call(other);
    }

    @WrapOperation(
            method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getEntityCollisions(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Ljava/util/List;"
            )
    )
    private List<VoxelShape> wathe$appendSolidPlayerMovementShapes(World world,
                                                                   Entity entity,
                                                                   Box movementBox,
                                                                   Operation<List<VoxelShape>> original) {
        List<VoxelShape> shapes = original.call(world, entity, movementBox);
        if (!(entity instanceof PlayerEntity selfPlayer)) {
            return shapes;
        }

        /*
         * 这里是 SOLID 手感的核心兜底：EntityView#getEntityCollisions 和 Entity#collidesWith
         * 已经会询问 PlayerCollisionApi，但玩家移动最终还是在 Entity#adjustMovementForCollisions
         * 里裁剪 Vec3d。把 SOLID 玩家碰撞箱在这里再补进一次，可以避免客户端预测路径因为接口默认方法、
         * 其它 mixin 顺序或原版 collidesWith 结果差异而漏掉玩家实体墙，减少“客户端先穿过去、服务端再拉回”。
         *
         * 同一个 shape 即使已经被原逻辑加入，再加入一次也只会重复参与 max offset 计算，不会改变最终裁剪结果；
         * 但当原列表因为某条调用链没有拿到 Wathe 规则时，这一层会把硬阻挡补齐。
         *
         * 不能只用 World#getOtherEntities(movementBox) 取候选玩家：TP 后客户端远端玩家的“当前 AABB”
         * 可能还停在插值途中的旧位置，导致空间索引暂时搜不到已经被服务端传送到身边的玩家。
         * 所以这里直接遍历本世界玩家，并用 PlayerCollisionShapeHelper 取服务端 lerpTarget 对应的碰撞箱；
         * 只要这个箱子落在本次移动范围内，就补进移动裁剪列表。
         */
        Box searchBox = movementBox.expand(1.0E-7D);
        ArrayList<VoxelShape> solidPlayerShapes = new ArrayList<>();
        for (PlayerEntity otherPlayer : world.getPlayers()) {
            if (otherPlayer == selfPlayer || !EntityPredicates.EXCEPT_SPECTATOR.test(otherPlayer)) {
                continue;
            }
            if (!PlayerCollisionApi.blocksMovement(selfPlayer, otherPlayer)) {
                continue;
            }

            Box collisionBox = PlayerCollisionShapeHelper.getMovementCollisionBox(otherPlayer);
            if (collisionBox.intersects(searchBox)) {
                solidPlayerShapes.add(VoxelShapes.cuboid(collisionBox));
            }
        }
        if (solidPlayerShapes.isEmpty()) {
            return shapes;
        }

        ArrayList<VoxelShape> merged = new ArrayList<>(shapes.size() + solidPlayerShapes.size());
        merged.addAll(shapes);
        merged.addAll(solidPlayerShapes);
        return List.copyOf(merged);
    }

    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void wathe$skipNoCollisionPlayerPush(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PlayerEntity selfPlayer
                && other instanceof PlayerEntity otherPlayer
                && PlayerCollisionApi.suppressesPush(selfPlayer, otherPlayer)) {
            /*
             * 原版玩家推挤一次会同时给双方加速度。
             * NO_COLLISION 要完全取消这份速度；SOLID 则只在双方已经重叠时保留它用于解卡。
             * 所以这里统一问 API，而不是只看单个模式，避免“实体墙”和“推挤速度”在正常贴墙时抢位置。
             */
            ci.cancel();
        }
    }

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void wathe$hideTargetVisibilityPlayer(PlayerEntity viewer, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof PlayerEntity target && !TargetVisibilityApi.canRenderPlayer(viewer, target)) {
            // 让玩家隐藏规则也覆盖原版 isInvisibleTo 查询，避免扩展继续为单个职业写实体可见性 mixin。
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void wathe$blockHiddenPlayerInteract(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!TargetVisibilityApi.canInteractWithEntity(player, (Entity) (Object) this)) {
            /*
             * 玩家右键实体属于服务端真实交互，不能只靠客户端准心隐藏。
             * 返回 PASS 与原版“没有可用交互”语义一致，也与旧扩展 mixin 的处理方式保持兼容。
             */
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
    private void wathe$blockHiddenPlayerInteractAt(PlayerEntity player,
                                                   Vec3d hitPos,
                                                   Hand hand,
                                                   CallbackInfoReturnable<ActionResult> cir) {
        if (!TargetVisibilityApi.canInteractWithEntity(player, (Entity) (Object) this)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }
}
