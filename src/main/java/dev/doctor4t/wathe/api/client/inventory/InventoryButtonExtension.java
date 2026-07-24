package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.NotNull;

/**
 * 单个背包界面实例上的按钮扩展。
 *
 * <p>provider 每次打开背包都会创建一个新的 extension，因此这里可以安全保存当前页码、
 * 已创建 widget、选中目标等“只属于当前屏幕”的临时状态。跨开局/停局仍要保存的页码，
 * 请使用 {@link InventoryPageState} 这类全局客户端缓存。</p>
 */
@Environment(EnvType.CLIENT)
public interface InventoryButtonExtension {
    default void init(@NotNull InventoryButtonContext context) {
    }

    default void tick(@NotNull InventoryButtonContext context) {
    }

    default void render(@NotNull InventoryButtonContext context,
                        @NotNull DrawContext drawContext,
                        int mouseX,
                        int mouseY,
                        float delta) {
    }

    /**
     * 返回 false 时，Wathe 会拦下“背包键关闭界面”的行为。
     *
     * <p>这个钩子用于替代扩展 mod 过去混入 LimitedHandledScreen 的 DoNotClose 逻辑，
     * 例如猜测者/造尸怪在文本输入阶段按 E 不应该直接关掉背包。</p>
     */
    default boolean allowInventoryKeyClose(@NotNull InventoryButtonContext context, int keyCode, int scanCode) {
        return true;
    }

    default void close(@NotNull InventoryButtonContext context) {
    }
}
