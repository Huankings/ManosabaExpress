package dev.doctor4t.wathe.client.instinct;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.appearance.ObserverRoleDisplayApi;
import dev.doctor4t.wathe.api.instinct.InstinctApi;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;

/** 非存活观察者的尸体职业色入口，扩展覆盖在独立的显示 API 优先级链内进行。 */
public final class ObserverBodyInstinctHandler {
    private ObserverBodyInstinctHandler() {}

    public static void register() {
        InstinctApi.registerDefaultHighlight(Wathe.id("observer_body_role_color"),
                ObserverRoleDisplayApi.OBSERVER_INSTINCT_PRIORITY, (viewer, target) -> {
                    if (!(target instanceof PlayerBodyEntity body)
                            || !GameFunctions.isPlayerSpectatingOrCreative(viewer)
                            || !WatheClient.isInstinctEnabled()) {
                        return InstinctApi.HighlightResult.pass();
                    }
                    // 未注册覆盖时沿用 owner 当前职业色，灵媒等机制可单独返回已冻结的显示色。
                    int color = ObserverRoleDisplayApi.resolveBodyColor(viewer, body,
                            WatheClient.getInstinctRoleHighlight(body.getPlayerUuid()));
                    return color < 0 ? InstinctApi.HighlightResult.hide() : InstinctApi.HighlightResult.color(color);
                });
    }
}
