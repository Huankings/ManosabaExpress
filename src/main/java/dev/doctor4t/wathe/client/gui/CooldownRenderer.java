package dev.doctor4t.wathe.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
/**
 *添加手持物品冷却时渲染冷却时间在该物品栏上方
 *冷却时间紧贴物品栏
 */

@Environment(EnvType.CLIENT)
public class CooldownRenderer {
    private static final int HOTBAR_TOP_Y_OFFSET = 22;
    private static final int GAP_ABOVE_HOTBAR = 1;

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, DrawContext context, RenderTickCounter tickCounter) {
        int selectedSlot = player.getInventory().selectedSlot;
        ItemStack heldStack = player.getInventory().main.get(selectedSlot);
        if (heldStack.isEmpty()) return;

        Item heldItem = heldStack.getItem();
        ItemCooldownManager manager = player.getItemCooldownManager();
        if (!manager.isCoolingDown(heldItem)) return;

        ItemCooldownManager.Entry entry = manager.entries.get(heldItem);
        if (entry == null) return;

        float remainingTicks = entry.endTick - (manager.tick + tickCounter.getTickDelta(true));
        if (remainingTicks <= 0f) return;

        String timeText = formatRemainingTime(remainingTicks / 20f);
        int slotCenterX = context.getScaledWindowWidth() / 2 - 90 + selectedSlot * 20 + 10;
        int textX = slotCenterX - renderer.getWidth(timeText) / 2;
        int textY = context.getScaledWindowHeight() - HOTBAR_TOP_Y_OFFSET - GAP_ABOVE_HOTBAR - renderer.fontHeight;

        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 200);
        context.drawTextWithShadow(renderer, timeText, textX, textY, 0xFFFFFFFF);
        context.getMatrices().pop();
    }

    private static String formatRemainingTime(float remainingSeconds) {
        if (remainingSeconds >= 60f) {
            int minutes = (int) (remainingSeconds / 60f);
            int seconds = MathHelper.ceil(remainingSeconds % 60f);
            if (seconds == 60) {
                minutes++;
                seconds = 0;
            }
            return String.format("%d:%02d", minutes, seconds);
        }

        if (remainingSeconds >= 10f) {
            return String.format("%ds", MathHelper.ceil(remainingSeconds));
        }

        return String.format("%.1fs", remainingSeconds);
    }
}
