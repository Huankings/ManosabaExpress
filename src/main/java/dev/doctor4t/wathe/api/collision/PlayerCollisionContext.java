package dev.doctor4t.wathe.api.collision;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

/**
 * 一次有方向的玩家碰撞判定。
 *
 * <p>{@code self -> other} 表示“当前正在移动、查询碰撞或发起推挤的一方”看向另一名玩家的结果。
 * 规则如果希望双向生效，应自行判断 {@link #self()} 和 {@link #other()} 任意一方是否满足条件。</p>
 */
public record PlayerCollisionContext(
        @NotNull PlayerEntity self,
        @NotNull PlayerEntity other,
        @NotNull World world,
        @NotNull GameWorldComponent game
) {
}
