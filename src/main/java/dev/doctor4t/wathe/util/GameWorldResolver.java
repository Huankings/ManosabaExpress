package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 统一解析“这条指令/流程真正应该作用在哪个 Wathe 世界”。
 *
 * <p>原版 Wathe 大多直接使用 {@code source.getWorld()}，在只有主世界时没问题；
 * 但地图投票会把游戏切到数据包维度里，如果控制台或管理员仍在主世界执行指令，
 * 就会把 setvisual / setTimer / allowjump 等指令改到错误维度。</p>
 */
public final class GameWorldResolver {
    private GameWorldResolver() {
    }

    public static boolean isGameBusy(@NotNull ServerWorld world) {
        return GameWorldComponent.KEY.get(world).getGameStatus() != GameWorldComponent.GameStatus.INACTIVE;
    }

    public static boolean hasBusyGame(@NotNull MinecraftServer server) {
        return findBusyGameWorld(server) != null;
    }

    @Nullable
    public static ServerWorld findBusyGameWorld(@NotNull MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            if (isGameBusy(world)) {
                return world;
            }
        }
        return null;
    }

    public static ServerWorld resolve(@NotNull ServerCommandSource source) {
        ServerWorld sourceWorld = source.getWorld();
        if (isGameBusy(sourceWorld)) {
            return sourceWorld;
        }

        ServerWorld busyWorld = findBusyGameWorld(source.getServer());
        if (busyWorld != null) {
            return busyWorld;
        }

        MapVotingComponent voting = MapVotingComponent.KEY.get(source.getServer().getScoreboard());
        ServerWorld selectedWorld = voting.getLastSelectedWorld();
        return selectedWorld != null ? selectedWorld : sourceWorld;
    }
}
