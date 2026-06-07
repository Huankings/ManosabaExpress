package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.PlayerInstinctComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 玩家个人本能键输入模式指令。
 *
 * <p>用法：
 * 1. {@code /instinct} 或 {@code /instinct key}：查询自己当前的本能键模式；
 * 2. {@code /instinct key true}：本能键改为“按一下开关”；
 * 3. {@code /instinct key false}：本能键改为“长按生效”。</p>
 *
 * <p>这个指令故意不加 OP 权限限制，因为它只修改执行者自己的玩家组件。
 * 换句话说，任何玩家都可以调整自己的操作习惯，但不能改变其他人的模式。</p>
 */
public class InstinctCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("instinct")
                        .then(CommandManager.literal("key")
                                .then(CommandManager.argument("toggleMode", BoolArgumentType.bool())
                                        .executes(context -> setToggleMode(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "toggleMode")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .executes(context -> query(context.getSource()))
        );
    }

    private static int setToggleMode(ServerCommandSource source, boolean toggleMode) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PlayerInstinctComponent component = PlayerInstinctComponent.KEY.get(player);
        component.setToggleModeEnabled(toggleMode);

        source.sendFeedback(
                () -> Text.literal("你的本能键模式已设置为：").formatted(Formatting.GREEN)
                        .append(modeText(toggleMode)),
                false
        );
        return 1;
    }

    private static int query(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        boolean toggleMode = PlayerInstinctComponent.KEY.get(player).isToggleModeEnabled();

        source.sendFeedback(
                () -> Text.literal("你的本能键当前模式：").formatted(Formatting.YELLOW)
                        .append(modeText(toggleMode)),
                false
        );
        return 1;
    }

    private static Text modeText(boolean toggleMode) {
        return Text.literal(toggleMode ? "开关模式" : "长按模式").formatted(Formatting.GOLD);
    }
}
