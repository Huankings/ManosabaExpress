package dev.doctor4t.wathe.client.util;

import dev.doctor4t.wathe.api.client.tooltip.ItemTooltipApi;
import dev.doctor4t.wathe.index.WatheItems;

/** Wathe 原生物品的标准 tooltip 注册清单。实际解析与冷却读秒统一由公开 API 负责。 */
public final class WatheItemTooltips {
    private WatheItemTooltips() {
    }

    public static void addTooltips() {
        /*
         * 所有原生物品都走同一份公开实现。没有实际 ItemCooldownManager 条目的物品只显示描述，
         * 因此描述物品和可冷却物品无需再维护两张容易漏改的清单。
         */
        ItemTooltipApi.registerItems(
                WatheItems.KNIFE,
                WatheItems.REVOLVER,
                WatheItems.DERRINGER,
                WatheItems.GRENADE,
                WatheItems.PSYCHO_MODE,
                WatheItems.POISON_VIAL,
                WatheItems.SCORPION,
                WatheItems.FIRECRACKER,
                WatheItems.LOCKPICK,
                WatheItems.CROWBAR,
                WatheItems.BODY_BAG,
                WatheItems.BLACKOUT,
                WatheItems.NOTE
        );
    }
}
