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
 * 控制 Wathe 对局中“存活玩家是否允许跳跃”的指令。
 *
 * <p>用法：
 * 1. {@code /wathe:allowjump}
 *    查询当前跳跃开关状态；
 * 2. {@code /wathe:allowjump <true|false>}
 *    动态开启或关闭局内存活玩家的跳跃能力。
 *
 * <p>这里的“存活玩家”定义与 Wathe 现有玩法保持一致：
 * 生存 / 冒险模式玩家视为仍在局内存活；
 * 创造 / 旁观模式玩家视为非存活玩家，不受该限制控制。
 */
public class AllowJumpCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:allowjump")
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
     * 更新“局内存活玩家是否允许跳跃”的运行时配置。
     *
     * <p>由于该值保存在世界组件里，
     * 所以不仅会立刻同步给当前在线客户端，也会随世界数据一起保存。
     */
    private static int execute(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setAlivePlayerJumpAllowed(enabled);

        Text stateText = Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD);
        source.sendFeedback(
                () -> Text.literal("局内存活玩家跳跃已设置为：").formatted(Formatting.GREEN).append(stateText),
                true
        );
        return 1;
    }

    /**
     * 查询当前保存的跳跃开关状态。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        Text stateText = Text.literal(game.isAlivePlayerJumpAllowed() ? "开启" : "关闭").formatted(Formatting.GOLD);

        source.sendFeedback(
                () -> Text.literal("局内存活玩家跳跃当前状态：").formatted(Formatting.YELLOW).append(stateText),
                false
        );
        return 1;
    }
}
