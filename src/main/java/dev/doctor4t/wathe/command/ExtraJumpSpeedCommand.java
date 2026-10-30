package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 调试所有 Wathe 局内存活玩家共用的疾跑起跳额外水平速度。
 *
 * <p>用法：
 * 1. {@code /wathe:extrajumpspeed} 查询当前值；
 * 2. {@code /wathe:extrajumpspeed <非负小数>} 修改当前值。</p>
 *
 * <p>原版值为固定 {@code 0.2}，不会乘速度/缓慢药水倍率。Wathe 默认设为 {@code 0.0}，
 * 防止跑跳绕过统一移动速度；例如设为 {@code 0.05} 时，只会额外叠加 0.05 水平速度。</p>
 */
public final class ExtraJumpSpeedCommand {
    private ExtraJumpSpeedCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:extrajumpspeed")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("speed", FloatArgumentType.floatArg(0.0F))
                                .executes(context -> set(
                                        context.getSource(),
                                        FloatArgumentType.getFloat(context, "speed")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 配置保存在实际承载 Wathe 对局的世界组件中，而不是命令执行者当前所在维度。
     * 修改后组件会立即同步，确保客户端预测和服务端权威移动使用同一个数值。
     */
    private static int set(ServerCommandSource source, float speed) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setExtraSprintJumpSpeed(speed);
        float appliedSpeed = game.getExtraSprintJumpSpeed();

        source.sendFeedback(
                () -> Text.literal("全局疾跑起跳额外水平速度已设置为：")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal(Float.toString(appliedSpeed)).formatted(Formatting.GOLD)),
                true
        );
        return 1;
    }

    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        float speed = game.getExtraSprintJumpSpeed();
        source.sendFeedback(
                () -> Text.literal("全局疾跑起跳额外水平速度当前为：")
                        .formatted(Formatting.YELLOW)
                        .append(Text.literal(Float.toString(speed)).formatted(Formatting.GOLD)),
                false
        );
        return 1;
    }
}
