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
 * 查询或切换渐进式地图重置的差异扫描子模式。
 *
 * <p>用法：
 * <ul>
 *     <li>{@code /wathe:setDifferentialReset}：查询当前状态；</li>
 *     <li>{@code /wathe:setDifferentialReset check}：显式查询当前状态；</li>
 *     <li>{@code /wathe:setDifferentialReset <true|false>}：开启或关闭差异重置。</li>
 * </ul>
 *
 * <p>该开关只在渐进式重置开启时生效。关闭后不会切到一次性重置，
 * 而是回退到原有的“完整分块渐进复制”，方便管理员直接对比两种渐进方案。</p>
 */
public final class SetDifferentialResetCommand {
    private SetDifferentialResetCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:setDifferentialReset")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("check")
                                .executes(context -> query(context.getSource())))
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setEnabled(
                                        context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 保存供后续地图重置任务读取的开关值。
     * GameWorldResolver 用于兼容地图投票切换到数据包维度后的控制台和跨维度管理员指令。
     */
    private static int setEnabled(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setDifferentialResetEnabled(enabled);

        source.sendFeedback(
                () -> Text.literal("渐进式地图差异重置已设置为：").formatted(Formatting.GREEN)
                        .append(stateText(enabled))
                        .append(Text.literal(game.isGradualResetEnabled()
                                ? "（将在下一次地图重置时生效）"
                                : "（渐进式重置当前关闭，此设置暂不生效）").formatted(Formatting.GRAY)),
                true
        );
        return 1;
    }

    /**
     * 同时显示父开关，避免管理员只看到差异模式已开启，却误以为一次性重置也会使用它。
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(
                () -> Text.literal("渐进式地图差异重置当前状态：").formatted(Formatting.YELLOW)
                        .append(stateText(game.isDifferentialResetEnabled()))
                        .append(Text.literal("，渐进式重置总开关：").formatted(Formatting.GRAY))
                        .append(stateText(game.isGradualResetEnabled())),
                false
        );
        return 1;
    }

    private static Text stateText(boolean enabled) {
        return Text.literal(enabled ? "开启" : "关闭")
                .formatted(enabled ? Formatting.GOLD : Formatting.DARK_GRAY);
    }
}
