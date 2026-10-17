package dev.doctor4t.wathe.client.input;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.input.ClientInputApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/** Wathe 本体局内按键限制通过公共输入 API 注册的默认规则。 */
public final class WatheClientInputRules {
    private static boolean registered;

    private WatheClientInputRules() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ClientInputApi.registerKeyBindingRule(
                Wathe.id("input/default_game_restrictions"),
                ClientInputApi.DEFAULT_PRIORITY,
                context -> shouldSuppress(context.client(), context.keyBinding())
                        ? ClientInputApi.KeyResult.block()
                        : ClientInputApi.KeyResult.pass()
        );
    }

    private static boolean shouldSuppress(MinecraftClient client, net.minecraft.client.option.KeyBinding key) {
        ClientPlayerEntity player = client.player;
        if (player == null || !WatheClient.isPlayerAliveAndInSurvival()) {
            return false;
        }

        GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
        if (game == null || !game.isRunning()) {
            return false;
        }

        /*
         * 这条规则保留 Wathe 原有的局内禁键语义，但现在只是 priority 0 的默认结果。
         * 扩展职业可用更高优先级返回 ALLOW 或 OVERRIDE，只放开自己的窄场景，
         * 无需再次注入 KeyBinding 并依赖 mixin priority 覆盖 Wathe。
         */
        return key.equals(client.options.swapHandsKey)
                || (!game.isAlivePlayerJumpAllowed() && key.equals(client.options.jumpKey))
                || key.equals(client.options.togglePerspectiveKey)
                || key.equals(client.options.dropKey)
                || key.equals(client.options.advancementsKey);
    }
}
