package dev.doctor4t.wathe.api.blackout;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.NotNull;

/**
 * 扩展模组修改停电持续时间时拿到的上下文。
 *
 * <p>这里目前只暴露服务端世界和对局组件，后续如果要按地图、游戏模式、
 * 触发者或特殊事件动态改时长，可以继续在不破坏旧 handler 的前提下扩展。</p>
 */
public record BlackoutDurationContext(
        @NotNull ServerWorld world,
        @NotNull GameWorldComponent gameWorld
) {
}
