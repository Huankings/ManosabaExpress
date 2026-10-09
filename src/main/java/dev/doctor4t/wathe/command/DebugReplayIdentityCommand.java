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

/** 回放专用的玩家名字伪装调试指令；不会修改实时聊天、Tab、皮肤或真实 UUID。 */
public final class DebugReplayIdentityCommand {
    private static final SimpleCommandExceptionType SINGLE_PROFILE =
            new SimpleCommandExceptionType(Text.literal("回放身份目标必须恰好解析为一名玩家"));

    private DebugReplayIdentityCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wathe:debugReplayIdentity")
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
        component(source).setReplayIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList(),
                identity.getId(), identity.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家的回放身份显示为 ").formatted(Formatting.GREEN))
                .append(Text.literal(identity.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return players.size();
    }

    private static int setGlobal(ServerCommandSource source, GameProfile identity, long expiresAtMillis) {
        component(source).setReplayIdentityGlobal(identity.getId(), identity.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置所有玩家的回放身份显示为 ").formatted(Formatting.GREEN)
                .append(Text.literal(identity.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int clear(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int changed = component(source).clearReplayIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList());
        source.sendFeedback(() -> Text.literal("已解除 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家的回放身份伪装；若存在全局规则，这些玩家将排除全局伪装").formatted(Formatting.GREEN)), true);
        return Math.max(1, changed);
    }

    private static int clearAll(ServerCommandSource source) {
        component(source).clearReplayIdentityAll();
        source.sendFeedback(() -> Text.literal("已解除所有回放身份伪装规则").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        DebugDisguiseComponent component = component(source);
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry global = component.getReplayIdentityGlobalEntry();
        if (DebugDisguiseComponent.isActive(global, now)) {
            source.sendFeedback(() -> Text.literal("回放身份全局伪装：").formatted(Formatting.GREEN)
                    .append(Text.literal(global.appearanceName()).formatted(Formatting.GOLD))
                    .append(Text.literal(formatRemaining(global.expiresAtMillis(), now)).formatted(Formatting.GRAY)), false);
        }
        component.getReplayIdentityEntriesSnapshot().forEach((uuid, entry) -> {
            if (DebugDisguiseComponent.isActive(entry, now)) {
                source.sendFeedback(() -> Text.literal(uuid + " -> " + entry.appearanceName() + " "
                        + formatRemaining(entry.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
            }
        });
        source.sendFeedback(() -> Text.literal("回放身份单人规则：" + component.getReplayIdentityEntriesSnapshot().size()
                + "，排除全局玩家：" + component.getReplayIdentityGlobalExclusionCount()).formatted(Formatting.GRAY), false);
        return Math.max(1, component.getReplayIdentityEntriesSnapshot().size());
    }

    private static String formatRemaining(long expiresAtMillis, long now) {
        return expiresAtMillis == 0L ? "（永久）"
                : "（剩余 " + Math.max(0L, (expiresAtMillis - now + 999L) / 1000L) + " 秒）";
    }

    private static DebugDisguiseComponent component(ServerCommandSource source) {
        return DebugDisguiseComponent.KEY.get(source.getServer().getScoreboard());
    }
}
