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
     * 3. self 与 other 都是 Wathe 定义下的“局内存活玩家”。
     *
     * <p>只要任一条件不满足，就退回原版 {@code Entity#collidesWith}，
     * 这样关闭指令后会立刻恢复原版行为，不会继续被这里锁死。
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
                return true;
            }
        }
        return original.call(other);
    }
}
