package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 心情死亡机制开关指令。
 *
 * <p>用法：
 * 1. /wathe:moodEffectDeath
 *    查询当前开关状态；
 * 2. /wathe:moodEffectDeath <true|false>
 *    开启或关闭“心情归零精神崩溃死亡”机制。
 *
 * <p>该指令会同时影响：
 * 1. 心情归零是否死亡；
 * 2. HUD 濒死警告文字；
 * 3. HUD 抖动与警告颜色效果。
 */
public class MoodEffectDeathCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:moodEffectDeath")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> execute(
                                        context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 更新心情死亡机制开关。
     */
    private static int execute(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        game.setMoodEffectDeathEnabled(enabled);

        Text stateText = Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD);
        source.sendFeedback(
                () -> Text.literal("心情死亡机制已设置为：").formatted(Formatting.GREEN).append(stateText),
                true
        );
        return 1;
    }

    /**
     * 查询当前心情死亡机制状态。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        Text stateText = Text.literal(game.isMoodEffectDeathEnabled() ? "开启" : "关闭").formatted(Formatting.GOLD);

        source.sendFeedback(
                () -> Text.literal("心情死亡机制当前状态：").formatted(Formatting.YELLOW).append(stateText),
                false
        );
        return 1;
    }
}
