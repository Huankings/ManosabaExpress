package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.TaskPointWorldComponent;
import dev.doctor4t.wathe.task.TaskPointSyncManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 任务点透视相关指令。
 *
 * <p>提供三组能力：
 * 1. reload：重新扫描地图并广播；
 * 2. refresh：不重扫，只把当前缓存重新发给客户端；
 * 3. autoRefresh：查询或切换“每局开始自动重扫任务点”开关。
 */
public class TaskPointCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:taskPoints")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("reload")
                                .executes(context -> reload(context.getSource())))
                        .then(CommandManager.literal("refresh")
                                .executes(context -> refresh(context.getSource())))
                        .then(CommandManager.literal("autoRefresh")
                                .executes(context -> queryAutoRefresh(context.getSource()))
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setAutoRefresh(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        ))))
                        .executes(context -> queryStatus(context.getSource()))
        );
    }

    private static int reload(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        TaskPointSyncManager.reloadAndBroadcast(world);

        int count = TaskPointWorldComponent.KEY.get(world).size();
        source.sendFeedback(
                () -> Text.literal("任务点记录已重载并同步，共记录 ").formatted(Formatting.GREEN)
                        .append(Text.literal(String.valueOf(count)).formatted(Formatting.GOLD))
                        .append(Text.literal(" 个任务点").formatted(Formatting.GREEN)),
                true
        );
        return 1;
    }

    private static int refresh(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        TaskPointSyncManager.broadcast(world);

        int count = TaskPointWorldComponent.KEY.get(world).size();
        source.sendFeedback(
                () -> Text.literal("任务点透视缓存已重新同步，共发送 ").formatted(Formatting.GREEN)
                        .append(Text.literal(String.valueOf(count)).formatted(Formatting.GOLD))
                        .append(Text.literal(" 个任务点").formatted(Formatting.GREEN)),
                true
        );
        return 1;
    }

    private static int setAutoRefresh(ServerCommandSource source, boolean enabled) {
        TaskPointWorldComponent component = TaskPointWorldComponent.KEY.get(source.getWorld());
        component.setAutoRefreshOnGameStart(enabled);

        source.sendFeedback(
                () -> Text.literal("任务点开局自动重载已设置为：").formatted(Formatting.GREEN)
                        .append(Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD)),
                true
        );
        return 1;
    }

    private static int queryAutoRefresh(ServerCommandSource source) {
        TaskPointWorldComponent component = TaskPointWorldComponent.KEY.get(source.getWorld());
        source.sendFeedback(
                () -> Text.literal("任务点开局自动重载当前状态：").formatted(Formatting.YELLOW)
                        .append(Text.literal(component.isAutoRefreshOnGameStart() ? "开启" : "关闭").formatted(Formatting.GOLD)),
                false
        );
        return 1;
    }

    private static int queryStatus(ServerCommandSource source) {
        TaskPointWorldComponent component = TaskPointWorldComponent.KEY.get(source.getWorld());
        Text countText = Text.literal(String.valueOf(component.size())).formatted(Formatting.GOLD);
        Text autoRefreshText = Text.literal(component.isAutoRefreshOnGameStart() ? "开启" : "关闭").formatted(Formatting.GOLD);

        source.sendFeedback(
                () -> Text.literal("当前任务点记录数：").formatted(Formatting.YELLOW)
                        .append(countText)
                        .append(Text.literal("，开局自动重载：").formatted(Formatting.YELLOW))
                        .append(autoRefreshText),
                false
        );
        return 1;
    }
}
