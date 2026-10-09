package dev.doctor4t.wathe.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.doctor4t.wathe.cca.DebugDisguiseComponent;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collection;

/**
 * 管理员调试用“身份显示变形”指令。
 *
 * <p>它和玩家外观变形分开保存：玩家可以只改聊天/Tab/旁观菜单身份，仍保持真实皮肤；
 * 服务端聊天会以目标 UUID 和名称重新广播，客户端列表则按同一条记录显示目标名称和头像。</p>
 */
public final class DebugIdentityCommand {
    private static final SimpleCommandExceptionType SINGLE_PROFILE =
            new SimpleCommandExceptionType(Text.literal("身份伪装目标必须恰好解析为一名玩家"));

    private DebugIdentityCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wathe:debugIdentity")
                .requires(source -> source.hasPermissionLevel(2));
        root.then(CommandManager.literal("set")
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("identity", GameProfileArgumentType.gameProfile())
                                .then(CommandManager.literal("permanent").executes(context -> setPlayers(
                                        context.getSource(), EntityArgumentType.getPlayers(context, "targets"),
                                        getSingleProfile(context), 0L)))
                                .then(CommandManager.literal("seconds")
                                        .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                                .executes(context -> setPlayers(
                                                        context.getSource(), EntityArgumentType.getPlayers(context, "targets"),
                                                        getSingleProfile(context),
                                                        expiryFromSeconds(IntegerArgumentType.getInteger(context, "duration")))))))));
        root.then(CommandManager.literal("setAll")
                .then(CommandManager.argument("identity", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.literal("permanent").executes(context -> setGlobal(
                                context.getSource(), getSingleProfile(context), 0L)))
                        .then(CommandManager.literal("seconds")
                                .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                        .executes(context -> setGlobal(
                                                context.getSource(), getSingleProfile(context),
                                                expiryFromSeconds(IntegerArgumentType.getInteger(context, "duration"))))))));
        root.then(CommandManager.literal("clear")
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .executes(context -> clear(context.getSource(), EntityArgumentType.getPlayers(context, "targets")))));
        root.then(CommandManager.literal("clearAll").executes(context -> clearAll(context.getSource())));
        root.then(CommandManager.literal("list").executes(context -> list(context.getSource())));
        dispatcher.register(root);
    }

    private static long expiryFromSeconds(int seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private static GameProfile getSingleProfile(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgumentType.getProfileArgument(context, "identity");
        if (profiles.size() != 1) {
            throw SINGLE_PROFILE.create();
        }
        return profiles.iterator().next();
    }

    private static int setPlayers(ServerCommandSource source, Collection<ServerPlayerEntity> players,
                                  GameProfile identity, long expiresAtMillis) {
        component(source).setIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList(),
                identity.getId(), identity.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家的聊天/Tab/旁观身份显示为 ").formatted(Formatting.GREEN))
                .append(Text.literal(identity.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return players.size();
    }

    private static int setGlobal(ServerCommandSource source, GameProfile identity, long expiresAtMillis) {
        component(source).setIdentityGlobal(identity.getId(), identity.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置所有玩家的聊天/Tab/旁观身份显示为 ").formatted(Formatting.GREEN)
                .append(Text.literal(identity.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int clear(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int changed = component(source).clearIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList());
        source.sendFeedback(() -> Text.literal("已解除 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家的身份伪装；若存在全局规则，这些玩家将排除全局伪装").formatted(Formatting.GREEN)), true);
        return Math.max(1, changed);
    }

    private static int clearAll(ServerCommandSource source) {
        component(source).clearIdentityAll();
        source.sendFeedback(() -> Text.literal("已解除所有聊天/Tab/旁观身份伪装规则").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        DebugDisguiseComponent component = component(source);
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry global = component.getIdentityGlobalEntry();
        if (DebugDisguiseComponent.isActive(global, now)) {
            source.sendFeedback(() -> Text.literal("身份全局伪装：").formatted(Formatting.GREEN)
                    .append(Text.literal(global.appearanceName()).formatted(Formatting.GOLD))
                    .append(Text.literal(formatRemaining(global.expiresAtMillis(), now)).formatted(Formatting.GRAY)), false);
        }
        component.getIdentityEntriesSnapshot().forEach((uuid, entry) -> {
            if (DebugDisguiseComponent.isActive(entry, now)) {
                source.sendFeedback(() -> Text.literal(uuid + " -> " + entry.appearanceName() + " "
                        + formatRemaining(entry.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
            }
        });
        source.sendFeedback(() -> Text.literal("身份单人规则：" + component.getIdentityEntriesSnapshot().size()
                + "，排除全局玩家：" + component.getIdentityGlobalExclusionCount()).formatted(Formatting.GRAY), false);
        return Math.max(1, component.getIdentityEntriesSnapshot().size());
    }

    private static String formatRemaining(long expiresAtMillis, long now) {
        return expiresAtMillis == 0L ? "（永久）"
                : "（剩余 " + Math.max(0L, (expiresAtMillis - now + 999L) / 1000L) + " 秒）";
    }

    private static DebugDisguiseComponent component(ServerCommandSource source) {
        return DebugDisguiseComponent.KEY.get(source.getServer().getScoreboard());
    }
}
