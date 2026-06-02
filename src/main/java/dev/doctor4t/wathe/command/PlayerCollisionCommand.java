package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 控制 Wathe 对局中“局内存活玩家碰撞体积”的指令。
 *
 * <p>用法：
 * 1. {@code /wathe:playerCollision}
 *    查询当前碰撞体积开关状态；
 * 2. {@code /wathe:playerCollision <true|false>}
 *    动态开启或关闭局内存活玩家之间的实体碰撞。
 *
 * <p>这里控制的是 Wathe 通过 mixin 额外强制出来的“实体墙式碰撞”，
 * 只在对局运行期间参与判断。
 * 关闭后会回退到原版 {@code Entity#collidesWith} 行为，
 * 不再把玩家之间的碰撞体积锁死为始终开启。
 */
public class PlayerCollisionCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:playerCollision")
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
     * 更新“局内存活玩家碰撞体积”的运行时配置。
     *
     * <p>该值保存在世界组件中：
     * 1. 修改后会立刻同步给客户端；
     * 2. 服务器重启后也会从世界 NBT 中恢复；
     * 3. 即使当前不在对局中，也可以提前改好，供下一局直接使用。
     */
    private static int execute(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        game.setAlivePlayerCollisionEnabled(enabled);

        Text stateText = Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD);
        source.sendFeedback(
                () -> Text.literal("局内存活玩家碰撞体积已设置为：").formatted(Formatting.GREEN).append(stateText),
                true
        );
        return 1;
    }

    /**
     * 查询当前保存的碰撞体积开关状态。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        Text stateText = Text.literal(game.isAlivePlayerCollisionEnabled() ? "开启" : "关闭").formatted(Formatting.GOLD);

        source.sendFeedback(
                () -> Text.literal("局内存活玩家碰撞体积当前状态：").formatted(Formatting.YELLOW).append(stateText),
                false
        );
        return 1;
    }
}
