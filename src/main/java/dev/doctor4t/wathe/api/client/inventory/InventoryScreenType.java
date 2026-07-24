package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 背包按钮 API 当前支持的界面类型。
 *
 * <p>LIMITED 是 Wathe 对局中替换出的限制背包；VANILLA / CREATIVE 则用于
 * StarryExpress 图鉴这类不依赖对局状态的普通背包按钮。扩展 provider 应该显式检查
 * 自己关心的类型，避免把对局职业按钮挂到原版背包或创造背包里。</p>
 */
@Environment(EnvType.CLIENT)
public enum InventoryScreenType {
    LIMITED,
    VANILLA,
    CREATIVE
}
