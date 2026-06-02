package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SetKillerCountCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:setkiller")
            .requires(source -> source.hasPermissionLevel(2)) // 仅管理员可用
            .then(CommandManager.argument("count", IntegerArgumentType.integer(-1)) // -1 代表恢复默认比例
                .executes(context -> execute(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))
        );
    }

    private static int execute(ServerCommandSource source, int count) {
        // 获取世界组件并存入数值
        GameWorldComponent component = GameWorldComponent.KEY.get(source.getWorld());
        component.setFixedKillerCount(count); 

        // 给予反馈
        if (count >= 0) {
            source.sendFeedback(() -> Text.literal("已将每局杀手数量固定为: ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(count)).formatted(Formatting.YELLOW)), true);
        } else {
            source.sendFeedback(() -> Text.literal("已清除固定数量，恢复为比例分配模式").formatted(Formatting.AQUA), true);
        }
        
        return 1;
    }
}