package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 心情体力惩罚开关指令。
 *
 * <p>用法：
 * 1. {@code /wathe:moodStaminaPenalty} 查询两个开关；
 * 2. {@code /wathe:moodStaminaPenalty mid <true|false>} 切换中等心情惩罚；
 * 3. {@code /wathe:moodStaminaPenalty depressive <true|false>} 切换低落心情惩罚。</p>
 */
public class MoodStaminaPenaltyCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:moodStaminaPenalty")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("mid")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setMid(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .then(CommandManager.literal("depressive")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setDepressive(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .executes(context -> query(context.getSource()))
        );
    }

    private static int setMid(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setMidMoodStaminaPenaltyEnabled(enabled);
        source.sendFeedback(
                () -> Text.literal("中等心情体力惩罚已设置为：").formatted(Formatting.GREEN)
                        .append(stateText(enabled)),
                true
        );
        return 1;
    }

    private static int setDepressive(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setDepressiveMoodStaminaPenaltyEnabled(enabled);
        source.sendFeedback(
                () -> Text.literal("低落心情体力惩罚已设置为：").formatted(Formatting.GREEN)
                        .append(stateText(enabled)),
                true
        );
        return 1;
    }

    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(
                () -> Text.literal("心情体力惩罚当前状态：").formatted(Formatting.YELLOW)
                        .append(Text.literal(" 中等=").formatted(Formatting.GRAY))
                        .append(stateText(game.isMidMoodStaminaPenaltyEnabled()))
                        .append(Text.literal(" 低落=").formatted(Formatting.GRAY))
                        .append(stateText(game.isDepressiveMoodStaminaPenaltyEnabled())),
                false
        );
        return 1;
    }

    private static Text stateText(boolean enabled) {
        return Text.literal(enabled ? "开启" : "关闭").formatted(enabled ? Formatting.GOLD : Formatting.DARK_GRAY);
    }
}
