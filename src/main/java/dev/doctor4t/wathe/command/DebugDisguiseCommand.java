package dev.doctor4t.wathe.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
 * 管理员调试变形指令。
 *
 * <p>指令只写入服务端的 DebugDisguiseComponent；玩家外观和准心名字由客户端 provider
 * 读取同一份状态后统一解析。这样客户端不能自行伪造状态，离线玩家也能通过 UUID 继续匹配。</p>
 */
public final class DebugDisguiseCommand {
    private static final SimpleCommandExceptionType SINGLE_PROFILE =
            new SimpleCommandExceptionType(Text.literal("变形目标必须恰好解析为一名玩家"));

    private DebugDisguiseCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wathe:debugDisguise")
                .requires(source -> source.hasPermissionLevel(2));

        root.then(CommandManager.literal("set")
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("appearance", GameProfileArgumentType.gameProfile())
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
                .then(CommandManager.argument("appearance", GameProfileArgumentType.gameProfile())
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

    private static GameProfile getSingleProfile(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgumentType.getProfileArgument(context, "appearance");
        if (profiles.size() != 1) {
            throw SINGLE_PROFILE.create();
        }
        return profiles.iterator().next();
    }

    private static int setPlayers(ServerCommandSource source,
                                  Collection<ServerPlayerEntity> players,
                                  GameProfile appearance,
                                  long expiresAtMillis) {
        DebugDisguiseComponent component = component(source);
        component.setPlayerEntries(players.stream().map(ServerPlayerEntity::getUuid).toList(),
                appearance.getId(), appearance.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家变形成 ").formatted(Formatting.GREEN))
                .append(Text.literal(appearance.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return players.size();
    }

    private static int setGlobal(ServerCommandSource source, GameProfile appearance, long expiresAtMillis) {
        component(source).setGlobal(appearance.getId(), appearance.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置所有玩家变形成 ").formatted(Formatting.GREEN)
                .append(Text.literal(appearance.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int clear(ServerCommandSource source, Collection<ServerPlayerEntity> players) {
        int changed = component(source).clearPlayers(players.stream().map(ServerPlayerEntity::getUuid).toList());
        source.sendFeedback(() -> Text.literal("已解除 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(players.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 名玩家的调试变形；若存在全局规则，这些玩家将排除全局变形").formatted(Formatting.GREEN)), true);
        // Brigadier 用正数表示命令成功；即使目标原本已解除，也应视为一次成功的幂等清理。
        return Math.max(1, changed);
    }

    private static int clearAll(ServerCommandSource source) {
        component(source).clearAll();
        source.sendFeedback(() -> Text.literal("已解除所有调试变形规则").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        DebugDisguiseComponent component = component(source);
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry global = component.getGlobalEntry();
        if (DebugDisguiseComponent.isActive(global, now)) {
            source.sendFeedback(() -> Text.literal("全局变形：").formatted(Formatting.GREEN)
                    .append(Text.literal(global.appearanceName()).formatted(Formatting.GOLD))
                    .append(Text.literal(formatRemaining(global.expiresAtMillis(), now)).formatted(Formatting.GRAY)), false);
        }
        component.getPlayerEntriesSnapshot().forEach((uuid, entry) -> {
            if (DebugDisguiseComponent.isActive(entry, now)) {
                source.sendFeedback(() -> Text.literal(uuid + " -> " + entry.appearanceName() + " "
                        + formatRemaining(entry.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
            }
        });
        source.sendFeedback(() -> Text.literal("单人规则：" + component.getPlayerEntryCount()
                + "，排除全局玩家：" + component.getGlobalExclusionCount()).formatted(Formatting.GRAY), false);
        return Math.max(1, component.getPlayerEntryCount());
    }

    private static String formatRemaining(long expiresAtMillis, long now) {
        if (expiresAtMillis == 0L) {
            return "（永久）";
        }
        return "（剩余 " + Math.max(0L, (expiresAtMillis - now + 999L) / 1000L) + " 秒）";
    }

    private static DebugDisguiseComponent component(ServerCommandSource source) {
        return DebugDisguiseComponent.KEY.get(source.getServer().getScoreboard());
    }
}
