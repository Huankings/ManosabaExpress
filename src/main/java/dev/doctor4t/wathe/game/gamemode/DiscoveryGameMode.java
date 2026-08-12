package dev.doctor4t.wathe.game.gamemode;

import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.cca.GameTimeComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.TrainWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.List;

public class DiscoveryGameMode extends GameMode {
    public DiscoveryGameMode(Identifier identifier) {
        // 默认游戏时长统一从 GameConstants 读取；开局人数默认值由世界配置提供，方便指令调试。
        super(identifier, GameConstants.DEFAULT_GAME_START_TIME, GameConstants.MIN_DISCOVERY_PLAYER_COUNT);
    }

    @Override
    public void initializeGame(ServerWorld serverWorld, GameWorldComponent gameWorldComponent, List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            gameWorldComponent.addRole(player, WatheRoles.DISCOVERY_CIVILIAN);
        }
    }

    @Override
    public void tickServerGameLoop(ServerWorld serverWorld, GameWorldComponent gameWorldComponent) {
        // stop game if ran out of time
        if (!GameTimeComponent.KEY.get(serverWorld).hasTime())
            GameFunctions.stopGame(serverWorld);
    }
}
