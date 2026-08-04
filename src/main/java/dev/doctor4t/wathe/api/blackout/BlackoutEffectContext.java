package dev.doctor4t.wathe.api.blackout;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 扩展模组分配停电期间药水效果时拿到的上下文。
 *
 * <p>这是服务端上下文：客户端黑幕只读取同步后的停电状态和玩家已有夜视效果，
 * 不参与决定玩家应该被发放夜视还是失明，避免客户端自己影响玩法结果。</p>
 */
public record BlackoutEffectContext(
        @NotNull ServerWorld world,
        @NotNull GameWorldComponent gameWorld,
        @NotNull ServerPlayerEntity player,
        @Nullable Role role
) {
}
