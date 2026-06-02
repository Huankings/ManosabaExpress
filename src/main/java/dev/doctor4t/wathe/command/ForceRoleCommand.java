package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.ScoreboardRoleSelectorComponent;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class ForceRoleCommand {
    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:forceRole").requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("killer").then(CommandManager.argument("players", EntityArgumentType.players())
                        .executes(context -> forceKiller(context.getSource(), EntityArgumentType.getPlayers(context, "players")))
                )).then(CommandManager.literal("vigilante").then(CommandManager.argument("players", EntityArgumentType.players())
                        .executes(context -> forceVigilante(context.getSource(), EntityArgumentType.getPlayers(context, "players")))
                ))
        );
    }

    private static int forceKiller(@NotNull ServerCommandSource source, @NotNull Collection<ServerPlayerEntity> players) {
    // 删掉 Wathe.executeSupporterCommand，直接执行逻辑
    ScoreboardRoleSelectorComponent component = ScoreboardRoleSelectorComponent.KEY.get(source.getServer().getScoreboard());
    component.forcedKillers.clear();
    for (ServerPlayerEntity player : players) component.forcedKillers.add(player.getUuid());
    source.sendFeedback(() -> net.minecraft.text.Text.literal("§a[Xtra]调试留下的痕迹"), false);
    return 1; // 1 通常代表命令执行成功
}

private static int forceVigilante(@NotNull ServerCommandSource source, @NotNull Collection<ServerPlayerEntity> players) {
    // 同理，直接执行内部逻辑
    ScoreboardRoleSelectorComponent component = ScoreboardRoleSelectorComponent.KEY.get(source.getServer().getScoreboard());
    component.forcedVigilantes.clear();
    for (ServerPlayerEntity player : players) component.forcedVigilantes.add(player.getUuid());
    source.sendFeedback(() -> net.minecraft.text.Text.literal("§a[Xtra]调试留下的痕迹"), false);
    return 1;
}
}