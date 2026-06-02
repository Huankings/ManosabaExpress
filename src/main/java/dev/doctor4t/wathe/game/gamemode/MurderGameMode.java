package dev.doctor4t.wathe.game.gamemode;

// 必须要有这些导入语句，编译器才能认识下面的代码
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.GameTimeComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.cca.GameRoundEndComponent;
import dev.doctor4t.wathe.cca.ScoreboardRoleSelectorComponent;
import dev.doctor4t.wathe.client.gui.RoleAnnouncementTexts;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.util.AnnounceWelcomePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class MurderGameMode extends GameMode {
    public MurderGameMode(Identifier identifier) {
        // 修改点 1: 这里的 1 表示只要有 1 个人就能启动，方便测试
        super(identifier, 10, 1);
    }

    private static int assignRolesAndGetKillerCount(@NotNull ServerWorld world, @NotNull List<ServerPlayerEntity> players, GameWorldComponent gameComponent) {
        // civilian base role, replaced for selected killers and vigilantes
        for (ServerPlayerEntity player : players) {
            gameComponent.addRole(player, WatheRoles.CIVILIAN);
        }

        // select roles
        ScoreboardRoleSelectorComponent roleSelector = ScoreboardRoleSelectorComponent.KEY.get(world.getScoreboard());
        int total = roleSelector.assignKillers(world, gameComponent, players, (int) Math.floor((double) players.size() / gameComponent.getKillerDividend()));
        roleSelector.assignVigilantes(world, gameComponent, players,  (int) Math.floor((double) players.size() / gameComponent.getVigilanteDividend()));
        return total;
    }

    @Override
    public void initializeGame(ServerWorld serverWorld, GameWorldComponent gameWorldComponent, List<ServerPlayerEntity> players) {
        int killerCount = assignRolesAndGetKillerCount(serverWorld, players, gameWorldComponent);
        /*
         * 左轮的发放放在这里而不是义警抽取阶段：
         * 1. 原版 wathe 没有扩展义警替换流程，因此普通义警依然会正常拿到左轮；
         * 2. 统一在“最终职业已经确定”之后发放，和 HarpyModLoader 的扩展流程保持一致；
         * 3. 以后如果再调整义警位的替换顺序，也不会出现先发枪再换职业的问题。
         */
        ScoreboardRoleSelectorComponent.giveRevolversToVanillaVigilantes(gameWorldComponent, players);

        for (ServerPlayerEntity player : players) {
            Role role = gameWorldComponent.getRole(player);
            RoleAnnouncementTexts.RoleAnnouncementText announcement = GameRoundEndComponent.getAnnouncementByFaction(role == null ? null : role.getFaction());
            ServerPlayNetworking.send(player, new AnnounceWelcomePayload(
                    RoleAnnouncementTexts.ROLE_ANNOUNCEMENT_TEXTS.indexOf(announcement),
                    killerCount,
                    players.size() - killerCount
            ));
        }
    }

    @Override
    public void tickServerGameLoop(ServerWorld serverWorld, GameWorldComponent gameWorldComponent) {
        GameFunctions.WinStatus winStatus = GameFunctions.WinStatus.NONE;

        // check if out of time
        if (!GameTimeComponent.KEY.get(serverWorld).hasTime())
            winStatus = GameFunctions.WinStatus.TIME;

        boolean civilianAlive = false;
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            // passive money
            if (gameWorldComponent.canUseKillerFeatures(player)) {
                Integer balanceToAdd = GameConstants.PASSIVE_MONEY_TICKER.apply(serverWorld.getTime());
                if (balanceToAdd > 0) PlayerShopComponent.KEY.get(player).addToBalance(balanceToAdd);
            }

            // check if some civilians are still alive
            if (gameWorldComponent.isInnocent(player) && !GameFunctions.isPlayerEliminated(player)) {
                civilianAlive = true;
            }
        }

        // check killer win condition (killed all civilians)
        if (!civilianAlive) {
            winStatus = GameFunctions.WinStatus.KILLERS;
        }

        // check passenger win condition (all killers are dead)
        if (winStatus == GameFunctions.WinStatus.NONE) {
            winStatus = GameFunctions.WinStatus.PASSENGERS;
            for (UUID player : gameWorldComponent.getAllKillerTeamPlayers()) {
                if (!GameFunctions.isPlayerEliminated(serverWorld.getPlayerByUuid(player))) {
                    winStatus = GameFunctions.WinStatus.NONE;
                }
            }
        }

        // game end on win and display
        if (winStatus != GameFunctions.WinStatus.NONE && gameWorldComponent.getGameStatus() == GameWorldComponent.GameStatus.ACTIVE) {
            GameRoundEndComponent.KEY.get(serverWorld).setRoundEndData(serverWorld.getPlayers(), winStatus);

            GameFunctions.stopGame(serverWorld);
        }
    }
}
