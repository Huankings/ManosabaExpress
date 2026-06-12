package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

/**
 * 地图投票管理指令。
 *
 * <p>设置 onlyop/randommapcount 后会立刻重开投票，保证新规则不会只在下一轮才生效。</p>
 */
public class MapVoteCommand {
    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:mapvoting")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> restart(context.getSource()))
                .then(CommandManager.literal("restart")
                        .executes(context -> restart(context.getSource())))
                .then(CommandManager.literal("onlyop")
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setOnlyOp(
                                        context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")
                                ))))
                .then(CommandManager.literal("randommapcount")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer())
                                .executes(context -> setRandomMapCount(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")
                                ))))
        );

        dispatcher.register(CommandManager.literal("wathe:mapvote")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> restart(context.getSource())));
    }

    private static MapVotingComponent getVoting(ServerCommandSource source) {
        return MapVotingComponent.KEY.get(source.getServer().getScoreboard());
    }

    private static int restart(ServerCommandSource source) {
        MapVotingComponent voting = getVoting(source);
        voting.restartVoting();
        source.sendFeedback(() -> Text.literal("地图投票已重新开始。"), true);
        return 1;
    }

    private static int setOnlyOp(ServerCommandSource source, boolean enabled) {
        MapVotingComponent voting = getVoting(source);
        voting.setOnlyOpVoting(enabled);
        voting.restartVoting();
        source.sendFeedback(() -> Text.literal("地图投票仅管理员可投票：").append(Text.literal(enabled ? "true" : "false")), true);
        return 1;
    }

    private static int setRandomMapCount(ServerCommandSource source, int count) {
        MapVotingComponent voting = getVoting(source);
        voting.setRandomMapCount(count);
        voting.restartVoting();
        source.sendFeedback(() -> Text.literal("地图投票随机候选数量已设置为：").append(Text.literal(Integer.toString(count))), true);
        return 1;
    }
}
