package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.UUID;

public class LockToSupportersCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    dispatcher.register(CommandManager.literal("wathe:lockToSupporters")
            // 将 UUID 检查改为普通的管理员权限检查
            .requires(source -> source.hasPermissionLevel(2)) 
            .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> execute(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))
    );
}

    private static int execute(ServerCommandSource source, boolean value) {
        GameWorldComponent.KEY.get(source.getWorld()).setLockedToSupporters(false);
        return 1;
    }

}
