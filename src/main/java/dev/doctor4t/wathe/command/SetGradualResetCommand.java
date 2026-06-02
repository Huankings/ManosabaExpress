package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 用于查询或切换渐进式地图重置功能的指令。
 *
 * <p>用法：
 * <ul>
 *     <li>{@code /wathe:setGradualReset}：查询当前状态</li>
 *     <li>{@code /wathe:setGradualReset <true|false>}：开启或关闭功能</li>
 * </ul>
 */
public class SetGradualResetCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:setGradualReset")
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
     * 更新供后续开局使用的渐进式重置开关值。
     */
    private static int execute(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        game.setGradualResetEnabled(enabled);

        Text stateText = Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD);
        source.sendFeedback(
                () -> Text.literal("渐进式地图重置已设置为: ").formatted(Formatting.GREEN).append(stateText),
                true
        );
        return 1;
    }

    /**
     * 显示当前保存的渐进式重置开关状态。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        Text stateText = Text.literal(game.isGradualResetEnabled() ? "开启" : "关闭").formatted(Formatting.GOLD);

        source.sendFeedback(
                () -> Text.literal("渐进式地图重置当前状态: ").formatted(Formatting.YELLOW).append(stateText),
                false
        );
        return 1;
    }
}
