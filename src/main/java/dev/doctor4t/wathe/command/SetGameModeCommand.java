package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.command.argument.GameModeArgumentType;
import dev.doctor4t.wathe.api.GameMode;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SetGameModeCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:setmode")
            .requires(source -> source.hasPermissionLevel(2)) // 管理员权限
            .then(CommandManager.argument("mode", GameModeArgumentType.gameMode()) // 使用已有的模式参数类型
                .executes(context -> execute(context.getSource(), GameModeArgumentType.getGameModeArgument(context, "mode"))))
        );
    }

    private static int execute(ServerCommandSource source, GameMode mode) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(source.getWorld());
        
        // 1. 设置新模式
        gameWorld.setGameMode(mode);
        
        // 2. 强制同步数据到客户端
        gameWorld.sync();
        
        // 3. 反馈信息
        source.sendFeedback(() -> Text.literal("游戏模式已切换为: ").formatted(Formatting.GREEN)
            .append(Text.literal(mode.identifier.toString()).formatted(Formatting.GOLD)), true);
            
        source.sendFeedback(() -> Text.literal("提示: 如果模式未生效，请执行 /wathe:stop 重置当前回合。").formatted(Formatting.GRAY), false);
        
        return 1;
    }
}