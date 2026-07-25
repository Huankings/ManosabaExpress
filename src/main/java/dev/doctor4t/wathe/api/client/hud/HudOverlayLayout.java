package dev.doctor4t.wathe.api.client.hud;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通用 HUD provider 的轻量布局工具。
 *
 * <p>它只封装最常见的“右下角多行文字”和“准心附近居中文字”绘制，
 * 不保存任何职业状态。职业自己的判定仍放在各自扩展类里，避免把所有职业 HUD 堆成一个大类。</p>
 */
@Environment(EnvType.CLIENT)
public final class HudOverlayLayout {
    public static final int DEFAULT_RIGHT_MARGIN = 0;
    public static final int DEFAULT_BOTTOM_MARGIN = 0;

    private HudOverlayLayout() {
    }

    public static void drawBottomRightLine(@NotNull HudOverlayContext context, @NotNull Text line, int color) {
        drawBottomRightLines(context, List.of(line), color);
    }

    public static void drawBottomRightLines(@NotNull HudOverlayContext context, @NotNull List<Text> lines, int color) {
        drawBottomRightLines(context, lines, color, DEFAULT_RIGHT_MARGIN, DEFAULT_BOTTOM_MARGIN);
    }

    public static void drawBottomRightLines(@NotNull HudOverlayContext context,
                                            @NotNull List<Text> lines,
                                            int color,
                                            int rightMargin,
                                            int bottomMargin) {
        TextRenderer renderer = context.textRenderer();
        DrawContext drawContext = context.drawContext();
        int drawY = context.height() - bottomMargin;
        for (int index = lines.size() - 1; index >= 0; index--) {
            Text line = lines.get(index);
            drawY -= renderer.getWrappedLinesHeight(line, 999999);
            drawContext.drawTextWithShadow(
                    renderer,
                    line,
                    context.width() - rightMargin - renderer.getWidth(line),
                    drawY,
                    color
            );
        }
    }

    public static void drawCenteredNearCrosshair(@NotNull HudOverlayContext context,
                                                 @NotNull Text text,
                                                 int y,
                                                 int color) {
        drawCenteredNearCrosshair(context.textRenderer(), context.drawContext(), text, y, color);
    }

    public static void drawCenteredNearCrosshair(@NotNull TextRenderer renderer,
                                                 @NotNull DrawContext context,
                                                 @NotNull Text text,
                                                 int y,
                                                 int color) {
        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2.0F, context.getScaledWindowHeight() / 2.0F + 6.0F, 0.0F);
        context.getMatrices().scale(0.6F, 0.6F, 1.0F);
        context.drawTextWithShadow(renderer, text, -renderer.getWidth(text) / 2, y, color);
        context.getMatrices().pop();
    }
}
