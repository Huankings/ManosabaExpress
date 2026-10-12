package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;

/**
 * 心情任务全局调试设置指令。
 *
 * <p>所有设置都保存在 GameWorldComponent 中，因此它们对当前世界的全部玩家生效，
 * 并会随世界保存。任务槽位只限制最低值，不再以上限 3 截断，方便测试大量并行任务。</p>
 *
 * <p>用法：</p>
 * <p>{@code /wathe:moodTaskSettings}</p>
 * <p>{@code /wathe:moodTaskSettings drainMultiplier <true|false>}</p>
 * <p>{@code /wathe:moodTaskSettings spawnMode <threshold|random>}</p>
 * <p>{@code /wathe:moodTaskSettings maxSlots <数量>}</p>
 * <p>{@code /wathe:moodTaskSettings stuckCleanup <true|false>}</p>
 */
public final class MoodTaskSettingsCommand {
    private MoodTaskSettingsCommand() {
    }

    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:moodTaskSettings")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("drainMultiplier")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setDrainMultiplier(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .then(CommandManager.literal("spawnMode")
                                .then(CommandManager.literal("threshold")
                                        .executes(context -> setSpawnMode(context.getSource(), false)))
                                .then(CommandManager.literal("random")
                                        .executes(context -> setSpawnMode(context.getSource(), true)))
                                .executes(context -> query(context.getSource())))
                        .then(CommandManager.literal("maxSlots")
                                .then(CommandManager.argument(
                                                "slots",
                                                IntegerArgumentType.integer(GameConstants.MIN_CONFIGURED_MOOD_TASK_SLOTS)
                                        )
                                        .executes(context -> setMaxSlots(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "slots")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .then(CommandManager.literal("stuckCleanup")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setStuckCleanup(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        )))
                                .executes(context -> query(context.getSource())))
                        .executes(context -> query(context.getSource()))
        );
    }

    private static int setDrainMultiplier(@NotNull ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setMoodTaskDrainMultiplierEnabled(enabled);
        source.sendFeedback(
                () -> Text.literal("多任务心情倍率扣除已设置为：").formatted(Formatting.GREEN)
                        .append(stateText(enabled)),
                true
        );
        return 1;
    }

    private static int setSpawnMode(@NotNull ServerCommandSource source, boolean random) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setRandomMoodTaskSpawnEnabled(random);
        source.sendFeedback(
                () -> Text.literal("心情任务发放模式已设置为：").formatted(Formatting.GREEN)
                        .append(Text.literal(random ? "随机事件" : "固定心情阈值").formatted(Formatting.GOLD)),
                true
        );
        return 1;
    }

    private static int setMaxSlots(@NotNull ServerCommandSource source, int slots) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setConfiguredMoodTaskSlots(slots);
        source.sendFeedback(
                () -> Text.literal("最大心情任务槽位已设置为：").formatted(Formatting.GREEN)
                        .append(Text.literal(Integer.toString(game.getConfiguredMoodTaskSlots())).formatted(Formatting.GOLD)),
                true
        );
        return 1;
    }

    private static int setStuckCleanup(@NotNull ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setStuckMoodTaskCleanupEnabled(enabled);
        source.sendFeedback(
                () -> Text.literal("卡死任务自动清理已设置为：").formatted(Formatting.GREEN)
                        .append(stateText(enabled)),
                true
        );
        return 1;
    }

    private static int query(@NotNull ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(
                () -> Text.literal("心情任务设置：").formatted(Formatting.YELLOW)
                        .append(Text.literal(" 倍率扣除=").formatted(Formatting.GRAY))
                        .append(stateText(game.isMoodTaskDrainMultiplierEnabled()))
                        .append(Text.literal(" 模式=").formatted(Formatting.GRAY))
                        .append(Text.literal(game.isRandomMoodTaskSpawnEnabled() ? "随机事件" : "固定心情阈值").formatted(Formatting.GOLD))
                        .append(Text.literal(" 最大槽位=").formatted(Formatting.GRAY))
                        .append(Text.literal(Integer.toString(game.getConfiguredMoodTaskSlots())).formatted(Formatting.GOLD))
                        .append(Text.literal(" 卡死清理=").formatted(Formatting.GRAY))
                        .append(stateText(game.isStuckMoodTaskCleanupEnabled())),
                false
        );
        return 1;
    }

    private static @NotNull Text stateText(boolean enabled) {
        return Text.literal(enabled ? "开启" : "关闭")
                .formatted(enabled ? Formatting.GOLD : Formatting.DARK_GRAY);
    }
}
