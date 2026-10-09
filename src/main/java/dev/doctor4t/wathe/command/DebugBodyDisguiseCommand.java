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
 * 管理员调试用尸体变形指令。
 *
 * <p>尸体规则的键是尸体真实 owner 的 UUID，目标 UUID 只决定客户端显示的原始皮肤。
 * 指令不会改写 PlayerBodyEntity 的 owner、尸袋交互对象或回放数据，避免调试外观污染玩法身份。</p>
 */
public final class DebugBodyDisguiseCommand {
    private static final SimpleCommandExceptionType SINGLE_PROFILE =
            new SimpleCommandExceptionType(Text.literal("尸体变形目标必须恰好解析为一名玩家"));

    private DebugBodyDisguiseCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("wathe:debugBodyDisguise")
                .requires(source -> source.hasPermissionLevel(2));

        root.then(CommandManager.literal("set")
                .then(CommandManager.argument("owners", EntityArgumentType.players())
                        .then(CommandManager.argument("appearance", GameProfileArgumentType.gameProfile())
                                .then(CommandManager.literal("permanent").executes(context -> setOwners(
                                        context.getSource(), EntityArgumentType.getPlayers(context, "owners"),
                                        getSingleProfile(context), 0L)))
                                .then(CommandManager.literal("seconds")
                                        .then(CommandManager.argument("duration", IntegerArgumentType.integer(1))
                                                .executes(context -> setOwners(
                                                        context.getSource(), EntityArgumentType.getPlayers(context, "owners"),
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
                .then(CommandManager.argument("owners", EntityArgumentType.players())
                        .executes(context -> clear(context.getSource(), EntityArgumentType.getPlayers(context, "owners")))));
        root.then(CommandManager.literal("clearAll").executes(context -> clearAll(context.getSource())));
        root.then(CommandManager.literal("list").executes(context -> list(context.getSource())));
        dispatcher.register(root);
    }

    private static long expiryFromSeconds(int seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private static GameProfile getSingleProfile(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgumentType.getProfileArgument(context, "appearance");
        if (profiles.size() != 1) {
            throw SINGLE_PROFILE.create();
        }
        return profiles.iterator().next();
    }

    private static int setOwners(ServerCommandSource source, Collection<ServerPlayerEntity> owners,
                                 GameProfile appearance, long expiresAtMillis) {
        component(source).setBodyEntries(owners.stream().map(ServerPlayerEntity::getUuid).toList(),
                appearance.getId(), appearance.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(owners.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 具尸体变形成 ").formatted(Formatting.GREEN))
                .append(Text.literal(appearance.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return owners.size();
    }

    private static int setGlobal(ServerCommandSource source, GameProfile appearance, long expiresAtMillis) {
        component(source).setBodyGlobal(appearance.getId(), appearance.getName(), expiresAtMillis);
        source.sendFeedback(() -> Text.literal("已设置所有尸体变形成 ").formatted(Formatting.GREEN)
                .append(Text.literal(appearance.getName()).formatted(Formatting.GOLD))
                .append(Text.literal(expiresAtMillis == 0L ? "（永久）" : "（限时）").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int clear(ServerCommandSource source, Collection<ServerPlayerEntity> owners) {
        int changed = component(source).clearBodyEntries(owners.stream().map(ServerPlayerEntity::getUuid).toList());
        source.sendFeedback(() -> Text.literal("已解除 ").formatted(Formatting.GREEN)
                .append(Text.literal(String.valueOf(owners.size())).formatted(Formatting.GOLD))
                .append(Text.literal(" 具尸体的调试变形；若存在全局规则，这些尸体将排除全局变形").formatted(Formatting.GREEN)), true);
        return Math.max(1, changed);
    }

    private static int clearAll(ServerCommandSource source) {
        component(source).clearBodyAll();
        source.sendFeedback(() -> Text.literal("已解除所有尸体调试变形规则").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        DebugDisguiseComponent component = component(source);
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry global = component.getBodyGlobalEntry();
        if (DebugDisguiseComponent.isActive(global, now)) {
            source.sendFeedback(() -> Text.literal("尸体全局变形：").formatted(Formatting.GREEN)
                    .append(Text.literal(global.appearanceName()).formatted(Formatting.GOLD))
                    .append(Text.literal(formatRemaining(global.expiresAtMillis(), now)).formatted(Formatting.GRAY)), false);
        }
        component.getBodyEntriesSnapshot().forEach((uuid, entry) -> {
            if (DebugDisguiseComponent.isActive(entry, now)) {
                source.sendFeedback(() -> Text.literal(uuid + " -> " + entry.appearanceName() + " "
                        + formatRemaining(entry.expiresAtMillis(), now)).formatted(Formatting.GRAY), false);
            }
        });
        source.sendFeedback(() -> Text.literal("尸体单人规则：" + component.getBodyEntriesSnapshot().size()
                + "，排除全局尸体：" + component.getBodyGlobalExclusionCount()).formatted(Formatting.GRAY), false);
        return Math.max(1, component.getBodyEntriesSnapshot().size());
    }

    private static String formatRemaining(long expiresAtMillis, long now) {
        return expiresAtMillis == 0L ? "（永久）"
                : "（剩余 " + Math.max(0L, (expiresAtMillis - now + 999L) / 1000L) + " 秒）";
    }

    private static DebugDisguiseComponent component(ServerCommandSource source) {
        return DebugDisguiseComponent.KEY.get(source.getServer().getScoreboard());
    }
}
