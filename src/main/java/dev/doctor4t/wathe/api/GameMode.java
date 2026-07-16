package dev.doctor4t.wathe.api;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class GameMode {
    public final Identifier identifier;
    public final int defaultStartTime;
    public final int minPlayerCount;

    /**
     * @param identifier 游戏模式标识
     * @param defaultStartTime 游戏模式开始时计时器的默认时间，以ticks为单位
     * @param minPlayerCount 开始游戏模式所需的最少玩家数量
     */
    public GameMode(Identifier identifier, int defaultStartTime, int minPlayerCount) {
        this.identifier = identifier;
        this.defaultStartTime = defaultStartTime;
        this.minPlayerCount = minPlayerCount;
    }

    public void tickCommonGameLoop() {}

    public void tickClientGameLoop() {}

    public abstract void tickServerGameLoop(ServerWorld serverWorld, GameWorldComponent gameWorldComponent);

    public abstract void initializeGame(ServerWorld serverWorld, GameWorldComponent gameWorldComponent, List<ServerPlayerEntity> players);

    public void finalizeGame(ServerWorld serverWorld, GameWorldComponent gameWorldComponent) {

    }
}
