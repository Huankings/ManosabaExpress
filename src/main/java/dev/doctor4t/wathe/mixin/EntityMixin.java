package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

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
        GameWorldComponent game = GameWorldComponent.KEY.get(this.world);
        if (game.isRunning() && game.isAlivePlayerCollisionEnabled()) {
            Entity self = (Entity) (Object) this;
            if (self instanceof PlayerEntity selfPlayer
                    && other instanceof PlayerEntity otherPlayer
                    && GameFunctions.isPlayerAliveAndSurvival(selfPlayer)
                    && GameFunctions.isPlayerAliveAndSurvival(otherPlayer)) {
                if (game.isAlivePlayerCollisionStartDelayActive()) {
                    return false;
                }
                return true;
            }
        }
        return original.call(other);
    }
}
