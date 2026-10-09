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

/** 只改变聊天栏发送者名称的调试指令；不会改变 Tab、旁观菜单或玩家实体。 */
public final class DebugChatIdentityCommand {
    private static final SimpleCommandExceptionType SINGLE_PROFILE =
            new SimpleCommandExceptionType(Text.literal("聊天身份目标必须恰好解析为一名玩家"));

    private DebugChatIdentityCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wathe:debugChatIdentity")
                .requires(source -> source.hasPermissionLevel(2));
        root.then(CommandManager.literal("set").then(CommandManager.argument("targets", EntityArgumentType.players())
                .then(CommandManager.argument("identity", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.literal("permanent").executes(c -> setPlayers(c.getSource(), EntityArgumentType.getPlayers(c, "targets"), getProfile(c), 0L)))
                        .then(CommandManager.literal("seconds").then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                .executes(c -> setPlayers(c.getSource(), EntityArgumentType.getPlayers(c, "targets"), getProfile(c), expiry(IntegerArgumentType.getInteger(c, "duration")))))))));
        root.then(CommandManager.literal("setAll").then(CommandManager.argument("identity", GameProfileArgumentType.gameProfile())
                .then(CommandManager.literal("permanent").executes(c -> setGlobal(c.getSource(), getProfile(c), 0L)))
                .then(CommandManager.literal("seconds").then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                        .executes(c -> setGlobal(c.getSource(), getProfile(c), expiry(IntegerArgumentType.getInteger(c, "duration"))))))));
        root.then(CommandManager.literal("clear").then(CommandManager.argument("targets", EntityArgumentType.players())
                .executes(c -> clear(c.getSource(), EntityArgumentType.getPlayers(c, "targets")))));
        root.then(CommandManager.literal("clearAll").executes(c -> clearAll(c.getSource())));
        root.then(CommandManager.literal("list").executes(c -> list(c.getSource())));
        dispatcher.register(root);
    }

    private static long expiry(int seconds) { return System.currentTimeMillis() + seconds * 1000L; }

    private static GameProfile getProfile(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgumentType.getProfileArgument(context, "identity");
        if (profiles.size() != 1) throw SINGLE_PROFILE.create();
        return profiles.iterator().next();
    }

    private static int setPlayers(ServerCommandSource source, Collection<ServerPlayerEntity> players, GameProfile identity, long expires) {
        component(source).setChatIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList(), identity.getId(), identity.getName(), expires);
        source.sendFeedback(() -> Text.literal("已设置 " + players.size() + " 名玩家的聊天栏身份为 " + identity.getName()
                + (expires == 0L ? "（永久）" : "（限时）")).formatted(Formatting.GREEN), true);
        return players.size();
    }

    private static int setGlobal(ServerCommandSource source, GameProfile identity, long expires) {
        component(source).setChatIdentityGlobal(identity.getId(), identity.getName(), expires);
        source.sendFeedback(() -> Text.literal("已设置所有玩家的聊天栏身份为 " + identity.getName()
                + (expires == 0L ? "（永久）" : "（限时）")).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int clear(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int changed = component(source).clearChatIdentityEntries(players.stream().map(ServerPlayerEntity::getUuid).toList());
        source.sendFeedback(() -> Text.literal("已解除 " + players.size() + " 名玩家的聊天栏身份伪装；存在全局规则时排除全局伪装").formatted(Formatting.GREEN), true);
        return Math.max(1, changed);
    }

    private static int clearAll(ServerCommandSource source) {
        component(source).clearChatIdentityAll();
        source.sendFeedback(() -> Text.literal("已解除所有聊天栏身份伪装规则").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        DebugDisguiseComponent component = component(source);
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry global = component.getChatIdentityGlobalEntry();
        if (DebugDisguiseComponent.isActive(global, now)) {
            source.sendFeedback(() -> Text.literal("聊天栏全局身份：" + global.appearanceName() + remaining(global.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
        }
        component.getChatIdentityEntriesSnapshot().forEach((uuid, entry) -> {
            if (DebugDisguiseComponent.isActive(entry, now)) source.sendFeedback(() -> Text.literal(uuid + " -> " + entry.appearanceName() + remaining(entry.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
        });
        source.sendFeedback(() -> Text.literal("聊天栏单人规则：" + component.getChatIdentityEntriesSnapshot().size() + "，排除全局玩家：" + component.getChatIdentityGlobalExclusionCount()).formatted(Formatting.GRAY), false);
        return Math.max(1, component.getChatIdentityEntriesSnapshot().size());
    }

    private static String remaining(long expires, long now) { return expires == 0L ? "（永久）" : "（剩余 " + Math.max(0L, (expires - now + 999L) / 1000L) + " 秒）"; }
    private static DebugDisguiseComponent component(ServerCommandSource source) { return DebugDisguiseComponent.KEY.get(source.getServer().getScoreboard()); }
}
