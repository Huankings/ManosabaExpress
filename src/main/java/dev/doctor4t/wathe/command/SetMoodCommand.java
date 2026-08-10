package dev.doctor4t.wathe.command;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collection;

/**
 * 调试用心情设置指令。
 *
 * <p>用法：
 * 1. {@code /wathe:setMood <0-1>} 设置自己；
 * 2. {@code /wathe:setMood <0-1> <players>} 设置指定玩家。
 *
 * <p>最终仍然走 {@link PlayerMoodComponent#setMood(float)}，
 * 因此只有“真实心情”职业会保留指定值；无心情或假心情职业仍会显示为 1。</p>
 */
public class SetMoodCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:setMood")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("amount", FloatArgumentType.floatArg(0.0F, 1.0F))
                                .executes(context -> execute(
                                        context.getSource(),
                                        ImmutableList.of(context.getSource().getPlayerOrThrow()),
                                        FloatArgumentType.getFloat(context, "amount")
                                ))
                                .then(CommandManager.argument("targets", EntityArgumentType.players())
                                        .executes(context -> execute(
                                                context.getSource(),
                                                EntityArgumentType.getPlayers(context, "targets"),
                                                FloatArgumentType.getFloat(context, "amount")
                                        ))))
        );
    }

    private static int execute(ServerCommandSource source, Collection<ServerPlayerEntity> targets, float amount) {
        return Wathe.executeSupporterCommand(source, () -> {
            for (ServerPlayerEntity target : targets) {
                PlayerMoodComponent.KEY.get(target).setMood(amount);
            }
            source.sendFeedback(
                    () -> Text.literal("已将 ").formatted(Formatting.GREEN)
                            .append(Text.literal(String.valueOf(targets.size())).formatted(Formatting.GOLD))
                            .append(Text.literal(" 名玩家的心情设置为 ").formatted(Formatting.GREEN))
                            .append(Text.literal(String.valueOf(amount)).formatted(Formatting.GOLD)),
                    true
            );
        });
    }
}
