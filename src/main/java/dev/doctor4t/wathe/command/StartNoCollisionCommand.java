package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 控制“开局多少秒后才启用玩家碰撞体积”的指令。
 *
 * <p>用法：
 * 1. {@code /wathe:startnoCollision}
 *    查询当前开局无碰撞秒数；
 * 2. {@code /wathe:startnoCollision <seconds>}
 *    设置每局正式开始后，前多少秒不强制启用存活玩家碰撞体积。
 *
 * <p>注意：这个指令只是设置“延迟启用碰撞”的秒数。
 * 如果 {@code /wathe:playerCollision false} 已经关闭了碰撞体积总开关，
 * 那么这里配置的秒数会被保留，但不会实际生效。
 */
public class StartNoCollisionCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:startnoCollision")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0))
                                .executes(context -> execute(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 设置开局无碰撞的持续秒数。
     *
     * <p>秒数为 0 时表示取消保护期，也就是只要碰撞体积总开关开启，
     * 本局进入 ACTIVE 后就立刻恢复玩家之间的实体碰撞。
     */
    private static int execute(ServerCommandSource source, int seconds) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setAlivePlayersCollisionStartDelaySeconds(seconds);

        Text secondsText = Text.literal(seconds + " 秒").formatted(Formatting.GOLD);
        source.sendFeedback(
                () -> Text.literal("开局无碰撞时间已设置为：").formatted(Formatting.GREEN).append(secondsText),
                true
        );
        return 1;
    }

    /**
     * 查询当前保存的开局无碰撞秒数。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        int seconds = game.getAlivePlayersCollisionStartDelaySeconds();

        Text secondsText = Text.literal(seconds + " 秒").formatted(Formatting.GOLD);
        Text enabledText = Text.literal(game.isAlivePlayerCollisionEnabled() ? "碰撞体积总开关已开启" : "碰撞体积总开关已关闭")
                .formatted(game.isAlivePlayerCollisionEnabled() ? Formatting.GREEN : Formatting.RED);

        source.sendFeedback(
                () -> Text.literal("当前开局无碰撞时间：").formatted(Formatting.YELLOW)
                        .append(secondsText)
                        .append(Text.literal("（").formatted(Formatting.GRAY))
                        .append(enabledText)
                        .append(Text.literal("）").formatted(Formatting.GRAY)),
                false
        );
        return 1;
    }
}
