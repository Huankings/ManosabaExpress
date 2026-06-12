package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.TrainWorldComponent;
import dev.doctor4t.wathe.command.argument.TimeOfDayArgumentType;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;

import java.util.function.BiConsumer;

public class SetVisualCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:setVisual")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("snow")
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> execute(context.getSource(), TrainWorldComponent::setSnow, BoolArgumentType.getBool(context, "enabled")))))
                .then(CommandManager.literal("fog")
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> execute(context.getSource(), TrainWorldComponent::setFog, BoolArgumentType.getBool(context, "enabled")))))
                .then(CommandManager.literal("hud")
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> execute(context.getSource(), TrainWorldComponent::setHud, BoolArgumentType.getBool(context, "enabled")))))
                .then(CommandManager.literal("trainSpeed")
                        .then(CommandManager.argument("speed", IntegerArgumentType.integer(0))
                                .executes(context -> execute(context.getSource(), TrainWorldComponent::setSpeed, IntegerArgumentType.getInteger(context, "speed")))))
                .then(CommandManager.literal("time")
                        .then(CommandManager.argument("timeOfDay", TimeOfDayArgumentType.timeofday())
                                .executes(context -> executeTime(context.getSource(), TimeOfDayArgumentType.getTimeofday(context, "timeOfDay")))))
                .then(CommandManager.literal("resetMapEffects")
                        .executes(context -> reset(context.getSource())))
        );
    }

    private static int reset(ServerCommandSource source) {
        ServerWorld world = GameWorldResolver.resolve(source);
        GameWorldComponent.KEY.get(world).getMapEffect().initializeMapEffects(world, world.getPlayers());
        return 1;
    }

    private static <T> int execute(ServerCommandSource source, BiConsumer<TrainWorldComponent, T> consumer, T value) {
        return Wathe.executeSupporterCommand(source,
                () -> consumer.accept(TrainWorldComponent.KEY.get(GameWorldResolver.resolve(source)), value)
        );
    }

    private static int executeTime(ServerCommandSource source, TrainWorldComponent.TimeOfDay timeOfDay) {
        return Wathe.executeSupporterCommand(source,
                () -> {
                    /*
                     * 时间视觉和原版 /time set 保持同一行为：应用到所有维度。
                     * 这样当前地图维度不会在下一 tick 被其他维度旧组件抢回白天。
                     */
                    TrainWorldComponent.setServerTimeOfDay(source.getServer(), timeOfDay);
                }
        );
    }

}
