package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.command.argument.GameModeArgumentType;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

import java.util.Map;

/**
 * 控制 Wathe 准备区达到多少玩家后允许开局的指令。
 *
 * <p>用法：
 * 1. {@code /wathe:startPlayerCount}
 *    查询当前游戏模式保存的开局人数；
 * 2. {@code /wathe:startPlayerCount <players>}
 *    设置当前游戏模式的准备区人数门槛；
 * 3. {@code /wathe:startPlayerCount mode <gameMode> <players>}
 *    精确设置某个游戏模式的人数门槛；
 * 4. {@code /wathe:startPlayerCount list}
 *    查看所有已注册游戏模式的人数门槛。</p>
 *
 * <p>这个指令只负责“人数是否允许开局”。如果管理员把人数设得低于杀手比例分母，
 * Wathe 会把它视为调试测试局，不会再强行按 killerDividend 抬高人数。</p>
 */
public class StartPlayerCountCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:startPlayerCount")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("list")
                                .executes(context -> list(context.getSource()))
                        )
                        .then(CommandManager.literal("mode")
                                .then(CommandManager.argument("gameMode", GameModeArgumentType.gameMode())
                                        .then(CommandManager.argument("players", IntegerArgumentType.integer(1))
                                                .executes(context -> execute(
                                                        context.getSource(),
                                                        GameModeArgumentType.getGameModeArgument(context, "gameMode"),
                                                        IntegerArgumentType.getInteger(context, "players")
                                                )))
                                        .executes(context -> query(
                                                context.getSource(),
                                                GameModeArgumentType.getGameModeArgument(context, "gameMode")
                                        ))
                                )
                        )
                        .then(CommandManager.argument("players", IntegerArgumentType.integer(1))
                                .executes(context -> execute(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "players")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 保存当前游戏模式新的准备区开局人数。
     *
     * <p>保存后立刻同步到客户端，因此大厅自动开局 HUD 会马上刷新成新的人数提示。</p>
     */
    private static int execute(ServerCommandSource source, int players) {
        ServerWorld targetWorld = GameWorldResolver.resolve(source);
        GameWorldComponent game = GameWorldComponent.KEY.get(targetWorld);
        /*
         * 当前大厅的 gameMode 可能仍是 Wathe 的 Murder，
         * 但扩展（例如 HarpyModLoader）会在真正开局前把它解析成自己的模式。
         * 这里必须保存解析后的模式人数，否则管理员直接执行
         * /wathe:startPlayerCount <人数> 时，改到的可能不是实际开局使用的配置。
         */
        GameMode resolvedGameMode = GameFunctions.resolveStartGameMode(targetWorld, game.getGameMode());
        return execute(source, resolvedGameMode, players);
    }

    /**
     * 保存指定游戏模式新的准备区开局人数。
     */
    private static int execute(ServerCommandSource source, GameMode gameMode, int players) {
        ServerWorld targetWorld = GameWorldResolver.resolve(source);
        GameWorldComponent game = GameWorldComponent.KEY.get(targetWorld);
        Identifier modeId = gameMode == null ? null : gameMode.identifier;
        game.setRequiredStartPlayerCountSetting(modeId, players);

        int effectivePlayers = GameFunctions.getRequiredStartPlayerCount(targetWorld, gameMode);
        source.sendFeedback(
                () -> Text.literal("Wathe 开局人数已设置为：").formatted(Formatting.GREEN)
                        .append(formatModeName(gameMode))
                        .append(Text.literal(" -> ").formatted(Formatting.GRAY))
                        .append(Text.literal(players + " 人").formatted(Formatting.GOLD))
                        .append(Text.literal("（实际门槛：").formatted(Formatting.GRAY))
                        .append(Text.literal(effectivePlayers + " 人").formatted(Formatting.YELLOW))
                        .append(Text.literal("）").formatted(Formatting.GRAY)),
                true
        );
        return 1;
    }

    /**
     * 查询当前游戏模式保存的开局人数配置。
     */
    private static int query(ServerCommandSource source) {
        ServerWorld targetWorld = GameWorldResolver.resolve(source);
        GameWorldComponent game = GameWorldComponent.KEY.get(targetWorld);
        // 查询当前大厅真正会启动的模式，避免 Harpy 默认替换时仍显示 Murder 配置。
        return query(source, GameFunctions.resolveStartGameMode(targetWorld, game.getGameMode()));
    }

    /**
     * 查询指定游戏模式保存的开局人数配置。
     */
    private static int query(ServerCommandSource source, GameMode gameMode) {
        ServerWorld targetWorld = GameWorldResolver.resolve(source);
        GameWorldComponent game = GameWorldComponent.KEY.get(targetWorld);
        Identifier modeId = gameMode == null ? null : gameMode.identifier;
        int configuredPlayers = game.getRequiredStartPlayerCountSetting(modeId);
        int effectivePlayers = GameFunctions.getRequiredStartPlayerCount(targetWorld, gameMode);

        source.sendFeedback(
                () -> Text.literal("当前 Wathe 开局人数配置：").formatted(Formatting.YELLOW)
                        .append(formatModeName(gameMode))
                        .append(Text.literal(" -> ").formatted(Formatting.GRAY))
                        .append(Text.literal(configuredPlayers + " 人").formatted(Formatting.GOLD))
                        .append(Text.literal("（实际门槛：").formatted(Formatting.GRAY))
                        .append(Text.literal(effectivePlayers + " 人").formatted(Formatting.YELLOW))
                        .append(Text.literal("）").formatted(Formatting.GRAY)),
                false
        );
        return 1;
    }

    /**
     * 列出当前已注册的所有游戏模式开局人数。
     *
     * <p>HarpyModLoader 等扩展模式只有在对应模组已经加载并注册后，才会出现在这里。</p>
     */
    private static int list(ServerCommandSource source) {
        ServerWorld targetWorld = GameWorldResolver.resolve(source);
        GameWorldComponent game = GameWorldComponent.KEY.get(targetWorld);
        Map<Identifier, Integer> overrides = game.getRequiredStartPlayerCountSettings();

        Text text = Text.literal("Wathe 各游戏模式开局人数：").formatted(Formatting.YELLOW);
        for (GameMode gameMode : WatheGameModes.GAME_MODES.values()) {
            Identifier modeId = gameMode.identifier;
            int configuredPlayers = game.getRequiredStartPlayerCountSetting(modeId);
            int effectivePlayers = GameFunctions.getRequiredStartPlayerCount(targetWorld, gameMode);
            boolean overridden = overrides.containsKey(modeId);
            text = text.copy()
                    .append(Text.literal("\n - ").formatted(Formatting.GRAY))
                    .append(formatModeName(gameMode))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(configuredPlayers + " 人").formatted(overridden ? Formatting.GOLD : Formatting.WHITE))
                    .append(Text.literal("（实际门槛 ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(effectivePlayers + " 人").formatted(Formatting.YELLOW))
                    .append(Text.literal(overridden ? "，已覆盖）" : "，默认）").formatted(Formatting.DARK_GRAY));
        }

        Text finalText = text;
        source.sendFeedback(() -> finalText, false);
        return 1;
    }

    private static Text formatModeName(GameMode gameMode) {
        if (gameMode == null) {
            return Text.literal("未知模式").formatted(Formatting.RED);
        }
        return Text.literal(gameMode.identifier.toString()).formatted(Formatting.AQUA);
    }
}
