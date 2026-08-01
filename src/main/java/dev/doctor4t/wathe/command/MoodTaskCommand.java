package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.doctor4t.wathe.api.task.MoodTaskApi;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * 心情任务调试指令。
 *
 * <p>这里提供三种面向管理员/开发测试的操作：</p>
 * <p>1. assign：指定给玩家发放某个注册任务；</p>
 * <p>2. remove：只删除某个任务，不加心情、不触发任务完成事件；</p>
 * <p>3. complete：按正常完成流程完成任务，会加心情、写回放并触发任务完成 API。</p>
 *
 * <p>如果没有显式传入 target，就默认作用于执行指令的玩家自己。
 * 这样局内自测时可以直接输入 {@code /wathe:moodTask assign wathe:sleep}。</p>
 */
public final class MoodTaskCommand {
    private MoodTaskCommand() {
    }

    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:moodTask")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("list")
                                .executes(context -> list(context.getSource())))
                        .then(CommandManager.literal("assign")
                                .then(CommandManager.argument("task", IdentifierArgumentType.identifier())
                                        .suggests((context, builder) -> CommandSource.suggestIdentifiers(MoodTaskApi.getRegisteredTaskIds(), builder))
                                        .executes(context -> assign(
                                                context.getSource(),
                                                IdentifierArgumentType.getIdentifier(context, "task"),
                                                getDefaultTarget(context.getSource())
                                        ))
                                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                                .executes(context -> assign(
                                                        context.getSource(),
                                                        IdentifierArgumentType.getIdentifier(context, "task"),
                                                        EntityArgumentType.getPlayer(context, "target")
                                                )))))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("task", IdentifierArgumentType.identifier())
                                        .suggests((context, builder) -> CommandSource.suggestIdentifiers(MoodTaskApi.getRegisteredTaskIds(), builder))
                                        .executes(context -> remove(
                                                context.getSource(),
                                                IdentifierArgumentType.getIdentifier(context, "task"),
                                                getDefaultTarget(context.getSource())
                                        ))
                                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                                .executes(context -> remove(
                                                        context.getSource(),
                                                        IdentifierArgumentType.getIdentifier(context, "task"),
                                                        EntityArgumentType.getPlayer(context, "target")
                                                )))))
                        .then(CommandManager.literal("complete")
                                .then(CommandManager.argument("task", IdentifierArgumentType.identifier())
                                        .suggests((context, builder) -> CommandSource.suggestIdentifiers(MoodTaskApi.getRegisteredTaskIds(), builder))
                                        .executes(context -> complete(
                                                context.getSource(),
                                                IdentifierArgumentType.getIdentifier(context, "task"),
                                                getDefaultTarget(context.getSource())
                                        ))
                                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                                .executes(context -> complete(
                                                        context.getSource(),
                                                        IdentifierArgumentType.getIdentifier(context, "task"),
                                                        EntityArgumentType.getPlayer(context, "target")
                                                )))))
        );
    }

    private static int list(@NotNull ServerCommandSource source) {
        String taskList = String.join(", ", MoodTaskApi.getRegisteredTaskIds().stream().map(Identifier::toString).toList());
        source.sendFeedback(
                () -> Text.literal("已注册心情任务：").formatted(Formatting.YELLOW)
                        .append(Text.literal(taskList.isEmpty() ? "无" : taskList).formatted(Formatting.GOLD)),
                false
        );
        return MoodTaskApi.getRegisteredTaskIds().size();
    }

    private static int assign(@NotNull ServerCommandSource source, @NotNull Identifier taskId, @NotNull ServerPlayerEntity target) {
        MoodTaskApi.TaskAssignmentResult result = MoodTaskApi.assignTask(target, taskId);
        if (result.success()) {
            source.sendFeedback(
                    () -> Text.literal("已发放心情任务：").formatted(Formatting.GREEN)
                            .append(Text.literal(taskId.toString()).formatted(Formatting.GOLD))
                            .append(Text.literal(" -> ").formatted(Formatting.GREEN))
                            .append(target.getDisplayName()),
                    true
            );
            return 1;
        }

        sendFailure(source, "发放心情任务失败", taskId, target, result.status().name());
        return 0;
    }

    private static int remove(@NotNull ServerCommandSource source, @NotNull Identifier taskId, @NotNull ServerPlayerEntity target) {
        MoodTaskApi.TaskOperationResult result = MoodTaskApi.removeTask(target, taskId);
        if (result.success()) {
            source.sendFeedback(
                    () -> Text.literal("已删除心情任务：").formatted(Formatting.GREEN)
                            .append(Text.literal(taskId.toString()).formatted(Formatting.GOLD))
                            .append(Text.literal(" -> ").formatted(Formatting.GREEN))
                            .append(target.getDisplayName()),
                    true
            );
            return 1;
        }

        sendFailure(source, "删除心情任务失败", taskId, target, result.status().name());
        return 0;
    }

    private static int complete(@NotNull ServerCommandSource source, @NotNull Identifier taskId, @NotNull ServerPlayerEntity target) {
        MoodTaskApi.TaskOperationResult result = MoodTaskApi.completeTask(target, taskId, true);
        if (result.success()) {
            source.sendFeedback(
                    () -> Text.literal("已按完成流程完成心情任务：").formatted(Formatting.GREEN)
                            .append(Text.literal(taskId.toString()).formatted(Formatting.GOLD))
                            .append(Text.literal(" -> ").formatted(Formatting.GREEN))
                            .append(target.getDisplayName()),
                    true
            );
            return 1;
        }

        sendFailure(source, "完成心情任务失败", taskId, target, result.status().name());
        return 0;
    }

    private static @NotNull ServerPlayerEntity getDefaultTarget(@NotNull ServerCommandSource source) throws CommandSyntaxException {
        return source.getPlayerOrThrow();
    }

    private static void sendFailure(
            @NotNull ServerCommandSource source,
            @NotNull String action,
            @NotNull Identifier taskId,
            @NotNull ServerPlayerEntity target,
            @NotNull String reason
    ) {
        source.sendError(
                Text.literal(action + "：")
                        .formatted(Formatting.RED)
                        .append(Text.literal(taskId.toString()).formatted(Formatting.GOLD))
                        .append(Text.literal(" -> ").formatted(Formatting.RED))
                        .append(target.getDisplayName())
                        .append(Text.literal("，原因：" + reason).formatted(Formatting.RED))
        );
    }
}
