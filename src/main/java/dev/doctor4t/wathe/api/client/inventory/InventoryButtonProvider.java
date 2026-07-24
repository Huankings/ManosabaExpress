package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 背包按钮 provider。
 *
 * <p>每次 screen 初始化时调用一次。返回 null 表示当前玩家/当前界面类型不需要任何按钮。
 * 返回 extension 后，Wathe 会在该 screen 生命周期内继续调用它的 init/render/tick/close。</p>
 */
@Environment(EnvType.CLIENT)
@FunctionalInterface
public interface InventoryButtonProvider {
    @Nullable InventoryButtonExtension create(@NotNull InventoryButtonContext context);
}
