package dev.doctor4t.wathe.api;

import dev.doctor4t.wathe.cca.PlayerLifeStateComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Wathe 玩法生命状态 API。
 *
 * <p>扩展职业需要让“局内存活玩家”临时进入 spectator 或 creative 时，
 * 请优先走这里，而不是直接调用 {@link ServerPlayerEntity#changeGameMode(GameMode)}。
 * 直接调用原版切模式会按普通管理员 / 原版指令处理，creative 和 spectator 仍会被视为非存活。</p>
 */
public final class PlayerLifeStateApi {
    /**
     * 当前线程内正在由 Wathe API 主动发起的特殊切模式玩家。
     *
     * <p>服务端命令和玩法逻辑都在主线程执行，用 ThreadLocal 可以避免把这个“正在切换中”的状态
     * 写进玩家组件，也能让 {@code ServerPlayerEntity.changeGameMode} 的 mixin 区分：
     * 这是 Wathe/扩展玩法授权的切模式，还是普通原版 /gamemode。</p>
     */
    private static final ThreadLocal<Set<UUID>> GAMEPLAY_ALIVE_GAME_MODE_CHANGES =
            ThreadLocal.withInitial(HashSet::new);

    private PlayerLifeStateApi() {
    }

    /**
     * 判断玩家是否拥有“creative / spectator 仍按存活处理”的特殊授权。
     */
    public static boolean hasAliveOverride(PlayerEntity player) {
        return player != null && PlayerLifeStateComponent.KEY.get(player).isAliveInNonSurvivalMode();
    }

    /**
     * 设置特殊存活授权。
     *
     * <p>一般扩展职业不需要直接调用这个方法；
     * 如果只是想切换游戏模式，请使用 {@link #changeGameModeAsGameplayAlive(ServerPlayerEntity, GameMode)}，
     * 它会一起处理原版模式切换和 Wathe 存活标记。</p>
     */
    public static void setAliveInCurrentGameMode(ServerPlayerEntity player, boolean alive) {
        if (player != null) {
            PlayerLifeStateComponent.KEY.get(player).setAliveInNonSurvivalMode(alive);
        }
    }

    /**
     * 清除特殊存活授权。
     *
     * <p>玩家真正死亡、回到大厅、普通 /gamemode 切换到旁观或创造时都应该清掉它，
     * 避免上一段玩法机制留下的标记污染后续状态。</p>
     */
    public static void clearAliveOverride(PlayerEntity player) {
        if (player != null) {
            PlayerLifeStateComponent.KEY.get(player).clearAliveInNonSurvivalMode();
        }
    }

    /**
     * 以“玩法仍存活”的身份切换原版游戏模式。
     *
     * <p>当目标模式是 creative 或 spectator 时，会先授予特殊存活标记，再执行原版切模式。
     * 当目标模式是 survival 或 adventure 时，会清掉特殊标记，因为这两种模式本身就会被 Wathe 判为存活。</p>
     */
    public static boolean changeGameModeAsGameplayAlive(ServerPlayerEntity player, GameMode gameMode) {
        if (player == null || gameMode == null) {
            return false;
        }

        if (isNonSurvivalMode(gameMode)) {
            PlayerLifeStateComponent.KEY.get(player).setAliveInNonSurvivalMode(true);
        } else {
            PlayerLifeStateComponent.KEY.get(player).clearAliveInNonSurvivalMode();
        }

        GAMEPLAY_ALIVE_GAME_MODE_CHANGES.get().add(player.getUuid());
        try {
            return player.changeGameMode(gameMode);
        } finally {
            Set<UUID> changingPlayers = GAMEPLAY_ALIVE_GAME_MODE_CHANGES.get();
            changingPlayers.remove(player.getUuid());
            if (changingPlayers.isEmpty()) {
                GAMEPLAY_ALIVE_GAME_MODE_CHANGES.remove();
            }
        }
    }

    /**
     * 供 Wathe 的 {@code ServerPlayerEntity.changeGameMode} mixin 查询当前切模式是否由本 API 授权。
     */
    public static boolean isGameplayAliveGameModeChangeAllowed(ServerPlayerEntity player) {
        return player != null && GAMEPLAY_ALIVE_GAME_MODE_CHANGES.get().contains(player.getUuid());
    }

    /**
     * 判断原版模式是否需要特殊授权才可被 Wathe 视为“玩法存活”。
     */
    public static boolean isNonSurvivalMode(GameMode gameMode) {
        return gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR;
    }
}
