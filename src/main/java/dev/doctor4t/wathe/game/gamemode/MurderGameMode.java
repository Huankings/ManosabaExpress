package dev.doctor4t.wathe.game.gamemode;

// 必须要有这些导入语句，编译器才能认识下面的代码
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.api.win.VictoryApi;
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
        /*
         * 第二个参数是开局后的默认游戏时长（tick），统一放到 GameConstants 里方便后续调整到秒。
         * 第三个参数只保留模式自身的硬性底线，真正默认 6 人门槛由 GameWorldComponent 的世界配置提供。
         * 这样管理员用 /wathe:startPlayerCount 调低人数时，可以正常开调试局。
         */
        super(identifier, GameConstants.DEFAULT_GAME_START_TIME, GameConstants.MIN_MURDER_PLAYER_COUNT);
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
            /*
             * 通用被动收入统一从 EconomyApi 结算。
             * 这样扩展职业可以声明自己拥有/禁止被动收入，也可以像“富豪”一样修改本次收入数值，
             * 不再需要 mixin 到 canUseKillerFeatures(...) 这个内部判断点。
             */
            int basePassiveIncome = GameConstants.PASSIVE_MONEY_TICKER.apply(serverWorld.getTime());
            int balanceToAdd = EconomyApi.calculatePassiveIncome(serverWorld, gameWorldComponent, player, basePassiveIncome);
            if (balanceToAdd > 0) {
                PlayerShopComponent playerShop = PlayerShopComponent.KEY.get(player);
                playerShop.addToBalance(balanceToAdd);
            }

            // 检查平民是否还存活
            if (gameWorldComponent.isInnocent(player) && !GameFunctions.isPlayerEliminated(player)) {
                civilianAlive = true;
            }
        }

        // 检查杀手胜利条件（杀死所有平民）
        if (!civilianAlive) {
            winStatus = GameFunctions.WinStatus.KILLERS;
        }

        // 检查乘客获胜条件（所有杀手都死了）
        if (winStatus == GameFunctions.WinStatus.NONE) {
            winStatus = GameFunctions.WinStatus.PASSENGERS;
            for (UUID player : gameWorldComponent.getAllKillerTeamPlayers()) {
                if (!GameFunctions.isPlayerEliminated(serverWorld.getPlayerByUuid(player))) {
                    winStatus = GameFunctions.WinStatus.NONE;
                }
            }
        }

        if (gameWorldComponent.getGameStatus() == GameWorldComponent.GameStatus.ACTIVE) {
            /*
             * 原版胜利状态已经算完后，统一交给公开胜利 API 做最后仲裁：
             * 1. 独立胜利职业 / 词条可以在这里直接写入自定义结算并结束；
             * 2. “活着时游戏不结束”的职业可以拦住普通杀手 / 乘客胜利；
             * 3. 允许共胜的词条可以在普通阵营胜利时追加真正赢家 UUID。
             *
             * 这样扩展模组不再需要 mixin 到本方法的局部变量或字节码字段位置。
             */
            VictoryApi.VictoryResult victoryResult = VictoryApi.evaluate(serverWorld, gameWorldComponent, winStatus);
            switch (victoryResult.action()) {
                case CUSTOM_WIN -> {
                    if (victoryResult.customVictory() != null) {
                        VictoryApi.endGameWithCustomVictory(serverWorld, victoryResult.customVictory());
                    }
                    return;
                }
                case KEEP_RUNNING -> {
                    return;
                }
                case VANILLA_WIN -> {
                    if (victoryResult.winStatus() != null) {
                        VictoryApi.endGameWithVanillaWin(serverWorld, victoryResult.winStatus(), victoryResult.extraWinnerUuids());
                        return;
                    }
                }
                case PASS -> {
                    // 没有扩展规则接管时，继续走 Wathe 原本的结算流程。
                }
            }
        }

        // 游戏胜利结束并显示
        if (winStatus != GameFunctions.WinStatus.NONE && gameWorldComponent.getGameStatus() == GameWorldComponent.GameStatus.ACTIVE) {
            GameRoundEndComponent.KEY.get(serverWorld).setRoundEndData(serverWorld.getPlayers(), winStatus);

            GameFunctions.stopGame(serverWorld);
        }
    }
}
