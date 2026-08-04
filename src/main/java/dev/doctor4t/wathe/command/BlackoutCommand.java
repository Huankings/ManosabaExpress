package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.blackout.BlackoutApi;
import dev.doctor4t.wathe.cca.WorldBlackoutComponent;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * 停电机制调试指令。
 *
 * <p>这些指令只改 Wathe 的停电世界组件，并通过组件同步把结果推给客户端。
 * 扩展模组需要触发或恢复停电时应优先调用 {@link BlackoutApi}，不要再 mixin 私有字段。</p>
 */
public class BlackoutCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:blackout")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("trigger")
                        .executes(context -> trigger(context.getSource())))
                .then(CommandManager.literal("restore")
                        .executes(context -> restore(context.getSource())))
                .then(CommandManager.literal("overlay")
                        .executes(context -> queryOverlay(context.getSource()))
                        .then(CommandManager.argument("opacity", IntegerArgumentType.integer(0, 100))
                                .executes(context -> setOverlay(context.getSource(), IntegerArgumentType.getInteger(context, "opacity")))))
                .then(CommandManager.literal("potionEffects")
                        .executes(context -> queryPotionEffects(context.getSource()))
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setPotionEffects(context.getSource(), BoolArgumentType.getBool(context, "enabled")))))
        );
    }

    private static int trigger(ServerCommandSource source) {
        return Wathe.executeSupporterCommand(source, () -> {
            ServerWorld world = GameWorldResolver.resolve(source);
            boolean triggered = BlackoutApi.trigger(world);
            source.sendFeedback(
                    () -> Text.translatable(triggered ? "command.wathe.blackout.triggered" : "command.wathe.blackout.already_active"),
                    true
            );
        });
    }

    private static int restore(ServerCommandSource source) {
        return Wathe.executeSupporterCommand(source, () -> {
            ServerWorld world = GameWorldResolver.resolve(source);
            BlackoutApi.restore(world);
            source.sendFeedback(() -> Text.translatable("command.wathe.blackout.restored"), true);
        });
    }

    private static int queryOverlay(ServerCommandSource source) {
        WorldBlackoutComponent blackout = WorldBlackoutComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(() -> Text.translatable("command.wathe.blackout.overlay.query", blackout.getOverlayOpacityPercent()), false);
        return 1;
    }

    private static int setOverlay(ServerCommandSource source, int opacity) {
        return Wathe.executeSupporterCommand(source, () -> {
            WorldBlackoutComponent blackout = WorldBlackoutComponent.KEY.get(GameWorldResolver.resolve(source));
            blackout.setOverlayOpacityPercent(opacity);
            source.sendFeedback(() -> Text.translatable("command.wathe.blackout.overlay.set", blackout.getOverlayOpacityPercent()), true);
        });
    }

    private static int queryPotionEffects(ServerCommandSource source) {
        WorldBlackoutComponent blackout = WorldBlackoutComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(
                () -> Text.translatable(blackout.arePotionEffectsEnabled()
                        ? "command.wathe.blackout.potion_effects.query.enabled"
                        : "command.wathe.blackout.potion_effects.query.disabled"),
                false
        );
        return 1;
    }

    private static int setPotionEffects(ServerCommandSource source, boolean enabled) {
        return Wathe.executeSupporterCommand(source, () -> {
            WorldBlackoutComponent blackout = WorldBlackoutComponent.KEY.get(GameWorldResolver.resolve(source));
            blackout.setPotionEffectsEnabled(enabled);
            source.sendFeedback(
                    () -> Text.translatable(enabled
                            ? "command.wathe.blackout.potion_effects.set.enabled"
                            : "command.wathe.blackout.potion_effects.set.disabled"),
                    true
            );
        });
    }
}
