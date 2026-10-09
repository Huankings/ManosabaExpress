package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.util.GameWorldResolver;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 调试用商店冷却开关。
 *
 * <p>该开关只放宽购买后给予物品的商店条目；购买即执行能力的 ACTION 条目仍由
 * Wathe 的统一购买流程保留冷却保护。</p>
 */
public final class ShopCooldownBypassCommand {
    private ShopCooldownBypassCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:shopCooldownBypass")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> set(
                                        context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")
                                )))
                        .executes(context -> query(context.getSource()))
        );
    }

    private static int set(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        game.setShopItemCooldownBypassEnabled(enabled);
        source.sendFeedback(
                () -> Text.literal("普通商店物品冷却购买绕过已设置为：")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal(enabled ? "开启" : "关闭").formatted(Formatting.GOLD)),
                true
        );
        return 1;
    }

    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(GameWorldResolver.resolve(source));
        source.sendFeedback(
                () -> Text.literal("普通商店物品冷却购买绕过当前状态：")
                        .formatted(Formatting.YELLOW)
                        .append(Text.literal(game.isShopItemCooldownBypassEnabled() ? "开启" : "关闭")
                                .formatted(Formatting.GOLD)),
                false
        );
        return 1;
    }
}
