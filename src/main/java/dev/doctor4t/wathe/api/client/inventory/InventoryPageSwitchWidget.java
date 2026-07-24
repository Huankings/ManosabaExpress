package dev.doctor4t.wathe.api.client.inventory;

import dev.doctor4t.wathe.util.ShopEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

/**
 * 背包玩家列表通用翻页按钮。
 */
@Environment(EnvType.CLIENT)
public class InventoryPageSwitchWidget extends ButtonWidget {
    private final ItemStack iconStack;
    private final Text tooltipText;

    public InventoryPageSwitchWidget(int x, int y, @NotNull ItemStack iconStack, @NotNull Text tooltipText, @NotNull PressAction onPress) {
        super(x, y, 16, 16, tooltipText, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.iconStack = iconStack;
        this.tooltipText = tooltipText;
    }

    @Override
    protected void renderWidget(@NotNull DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawGuiTexture(ShopEntry.Type.TOOL.getTexture(), this.getX() - 7, this.getY() - 7, 30, 30);
        context.drawItem(this.iconStack, this.getX(), this.getY());
        if (this.isHovered()) {
            this.drawHighlight(context);
            context.drawTooltip(MinecraftClient.getInstance().textRenderer, this.tooltipText, mouseX, mouseY);
        }
    }

    private void drawHighlight(@NotNull DrawContext context) {
        int color = -1862287543;
        int x = this.getX();
        int y = this.getY();
        context.fillGradient(RenderLayer.getGuiOverlay(), x, y, x + 16, y + 14, color, color, 0);
        context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 14, x + 15, y + 15, color, color, 0);
        context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 15, x + 14, y + 16, color, color, 0);
    }
}
