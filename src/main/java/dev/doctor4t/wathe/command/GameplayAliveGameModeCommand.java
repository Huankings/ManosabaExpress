package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameModeArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

/**
 * Wathe 调试用原版游戏模式切换命令。
 *
 * <p>它和原版 /gamemode 的区别是：
 * 当目标模式是 creative 或 spectator 时，如果目标玩家已经参与本局并拥有职业，
 * Wathe 会额外授予“玩法层仍视为存活”的标记。
 * 因此 HUD、胜负、心情、Tab/聊天限制、本能和职业名显示都会继续按存活玩家处理。</p>
 *
 * <p>普通原版 /gamemode 不会授予这个标记；
 * 使用原版 /gamemode creative 或 /gamemode spectator 时，旧标记也会被自动清除。</p>
 */
public class GameplayAliveGameModeCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:gamemode")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("mode", GameModeArgumentType.gameMode())
                        .executes(context -> execute(
                                context.getSource(),
                                GameModeArgumentType.getGameMode(context, "mode"),
                                context.getSource().getPlayerOrThrow()
                        ))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(context -> execute(
                                        context.getSource(),
                                        GameModeArgumentType.getGameMode(context, "mode"),
                                        EntityArgumentType.getPlayer(context, "player")
                                )))));
    }

    private static int execute(ServerCommandSource source, GameMode mode, ServerPlayerEntity target) throws CommandSyntaxException {
        if (PlayerLifeStateApi.isNonSurvivalMode(mode) && !hasRoleInCurrentRound(target)) {
            /*
             * 你确认过：没有参与本局、因此没有职业的玩家，不能因为调试命令被计入存活。
             * 这里也顺手清一次标记，避免旧局或手动调试残留污染当前旁观者。
             */
            PlayerLifeStateApi.clearAliveOverride(target);
            source.sendFeedback(
                    () -> Text.literal("该玩家未参与本局").formatted(Formatting.YELLOW),
                    false
            );
            return 0;
        }

        boolean specialAlive = PlayerLifeStateApi.isNonSurvivalMode(mode);
        if (specialAlive) {
            PlayerLifeStateApi.changeGameModeAsGameplayAlive(target, mode);
        } else {
            PlayerLifeStateApi.clearAliveOverride(target);
            target.changeGameMode(mode);
        }

        source.sendFeedback(
                () -> Text.literal("已将 ").formatted(Formatting.GREEN)
                        .append(target.getDisplayName())
                        .append(Text.literal(" 切换为 ").formatted(Formatting.GREEN))
                        .append(Text.literal(mode.getName()).formatted(Formatting.GOLD))
                        .append(Text.literal(specialAlive ? "（Wathe 玩法存活）" : "（普通存活模式）").formatted(Formatting.AQUA)),
                true
        );
        return 1;
    }

    private static boolean hasRoleInCurrentRound(ServerPlayerEntity player) {
        return GameWorldComponent.KEY.get(player.getWorld()).getRole(player) != null;
    }
}
