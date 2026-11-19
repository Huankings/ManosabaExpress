package dev.doctor4t.wathe.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.game.KillerStartingEconomy;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/** 挂在管理员 gameSettings 下，使用同一世界的杀手分母和经济设置；预览不会修改余额或消费调试余数。 */
final class KillerEconomyCommand {
    private KillerEconomyCommand() {
    }

    static LiteralArgumentBuilder<ServerCommandSource> build() {
        return CommandManager.literal("killerEconomy")
                .then(CommandManager.literal("check")
                        .executes(context -> preview(context.getSource(), GameFunctions.countReadyPlayers(context.getSource().getWorld()))))
                .then(CommandManager.literal("preview")
                        .then(CommandManager.argument("players", IntegerArgumentType.integer(0))
                                .executes(context -> preview(context.getSource(), IntegerArgumentType.getInteger(context, "players")))))
                .then(CommandManager.literal("coinsPerRemainder")
                        .then(CommandManager.literal("reset")
                                .executes(context -> setCoins(context.getSource(), GameConstants.DEFAULT_KILLER_START_MONEY_PER_REMAINDER_PLAYER)))
                        .then(CommandManager.argument("coins", IntegerArgumentType.integer(0))
                                .executes(context -> setCoins(context.getSource(), IntegerArgumentType.getInteger(context, "coins")))))
                .then(CommandManager.literal("remainder")
                        .then(CommandManager.literal("auto")
                                .executes(context -> setRemainder(context.getSource(), null)))
                        .then(CommandManager.argument("players", IntegerArgumentType.integer(0))
                                .executes(context -> setRemainder(context.getSource(), IntegerArgumentType.getInteger(context, "players")))));
    }

    private static int preview(ServerCommandSource source, int playerCount) {
        KillerStartingEconomy economy = GameWorldComponent.KEY.get(source.getWorld()).previewKillerStartingEconomy(playerCount);
        source.sendMessage(Text.literal("杀手开局经济预览（Murder / Harpy）\n"
                + "参赛 " + economy.playerCount() + " 人，杀手比例分母 " + economy.dividend()
                + "，自然杀手名额 " + economy.naturalKillerSlots() + "（不含强制职业）\n"
                + "自然余数 " + economy.naturalRemainder() + "，生效余数 " + economy.effectiveRemainder()
                + (economy.remainderOverride() == null ? "（自动）" : "（下局覆盖）")
                + "，每余 1 人 " + economy.coinsPerRemainder() + " 金币\n"
                + "每名最终杀手补贴 +" + economy.bonusCoins() + " 金币（无杀手时不发，余额达到整数上限时截断）"));
        return 1;
    }

    private static int setCoins(ServerCommandSource source, int coins) {
        GameWorldComponent.KEY.get(source.getWorld()).setKillerStartMoneyPerRemainderPlayer(coins);
        source.sendMessage(Text.literal("本世界杀手开局补贴：每余 1 人 +" + coins + " 金币"
                + (coins == 0 ? "（已关闭补贴）" : "（从下次开局生效）")));
        return 1;
    }

    private static int setRemainder(ServerCommandSource source, @Nullable Integer remainder) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        if (!game.setKillerStartingRemainderOverride(remainder)) {
            source.sendError(Text.literal("调试余数必须在 0 至 " + (game.getKillerDividend() - 1) + " 之间。"));
            return 0;
        }
        source.sendMessage(Text.literal(remainder == null ? "杀手开局补贴已恢复自动取余。"
                : "下一场 Murder / Harpy 开局计算补贴时按余 " + remainder + " 人计算，不改变杀手名额。"));
        return 1;
    }
}
