package dev.doctor4t.wathe.api.client.inventory;

import dev.doctor4t.wathe.client.gui.screen.ingame.LimitedInventoryScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

/**
 * 背包按钮 provider 访问当前 screen 的安全上下文。
 *
 * <p>扩展侧不要再继承/混入具体 screen 来拿 protected 字段，而是通过这里读取界面尺寸、
 * 背景坐标，并通过 addWidget / replaceGroup 让 Wathe 统一把控件挂到 screen 上。</p>
 */
@Environment(EnvType.CLIENT)
public final class InventoryButtonContext {
    private final Screen screen;
    private final InventoryScreenType type;
    private final ClientPlayerEntity player;
    private final TextRenderer textRenderer;
    private final InventoryButtonApi.WidgetAdder widgetAdder;
    private final InventoryButtonApi.ScreenState state;
    private final int width;
    private final int height;
    private final int backgroundX;
    private final int backgroundY;
    private final int backgroundWidth;
    private final int backgroundHeight;

    InventoryButtonContext(@NotNull Screen screen,
                           @NotNull InventoryScreenType type,
                           @Nullable ClientPlayerEntity player,
                           @NotNull TextRenderer textRenderer,
                           @NotNull InventoryButtonApi.WidgetAdder widgetAdder,
                           @NotNull InventoryButtonApi.ScreenState state,
                           int width,
                           int height,
                           int backgroundX,
                           int backgroundY,
                           int backgroundWidth,
                           int backgroundHeight) {
        this.screen = screen;
        this.type = type;
        this.player = player;
        this.textRenderer = textRenderer;
        this.widgetAdder = widgetAdder;
        this.state = state;
        this.width = width;
        this.height = height;
        this.backgroundX = backgroundX;
        this.backgroundY = backgroundY;
        this.backgroundWidth = backgroundWidth;
        this.backgroundHeight = backgroundHeight;
    }

    public @NotNull Screen screen() {
        return this.screen;
    }

    public @Nullable LimitedInventoryScreen limitedScreen() {
        return this.screen instanceof LimitedInventoryScreen limited ? limited : null;
    }

    public @NotNull LimitedInventoryScreen requireLimitedScreen() {
        LimitedInventoryScreen limited = this.limitedScreen();
        if (limited == null) {
            throw new IllegalStateException("This inventory button provider only supports LIMITED screens");
        }
        return limited;
    }

    public @NotNull InventoryScreenType type() {
        return this.type;
    }

    public @Nullable ClientPlayerEntity player() {
        return this.player;
    }

    public @NotNull ClientPlayerEntity requirePlayer() {
        return Objects.requireNonNull(this.player, "player");
    }

    public @NotNull TextRenderer textRenderer() {
        return this.textRenderer;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int backgroundX() {
        return this.backgroundX;
    }

    public int backgroundY() {
        return this.backgroundY;
    }

    public int backgroundWidth() {
        return this.backgroundWidth;
    }

    public int backgroundHeight() {
        return this.backgroundHeight;
    }

    public <T extends ClickableWidget> @NotNull T addWidget(@NotNull T widget) {
        /*
         * WidgetAdder 的真实实现来自 Screen#addDrawableChild。
         * 这里保留泛型返回值，是为了扩展侧创建 TextFieldWidget / 自定义按钮后还能继续访问原类型方法；
         * Wathe 不改变传入对象本身，所以这个类型转换是安全的。
         */
        @SuppressWarnings("unchecked")
        T added = (T) this.widgetAdder.add(widget);
        return added;
    }

    public <T extends ClickableWidget> @NotNull T addWidget(@NotNull Identifier groupId, @NotNull T widget) {
        T added = this.addWidget(widget);
        this.state.addToGroup(groupId, added);
        return added;
    }

    /**
     * 用一组新控件替换指定 group。
     *
     * <p>Minecraft Screen 在不同映射/版本里没有足够稳定的公开删除接口。
     * 因此 Wathe 的“删除”语义会把旧控件隐藏、禁用并解除焦点，再添加新控件。
     * 这样可以稳定支持 Convener 这类动态增删头像列表，也不会误删其它扩展挂到同一背包里的控件。</p>
     */
    public void replaceGroup(@NotNull Identifier groupId, @NotNull Collection<? extends ClickableWidget> widgets) {
        this.clearGroup(groupId);
        for (ClickableWidget widget : widgets) {
            this.addWidget(groupId, widget);
        }
    }

    public void clearGroup(@NotNull Identifier groupId) {
        this.state.clearGroup(groupId);
    }

    public void setGroupVisible(@NotNull Identifier groupId, boolean visible) {
        this.state.setGroupVisible(groupId, visible);
    }
}
