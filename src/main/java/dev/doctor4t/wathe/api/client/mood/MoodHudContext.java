package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 心情 HUD 每一帧渲染时提供给扩展模组的只读上下文。
 *
 * <p>扩展职业不应该再去 shadow {@code MoodRenderer} 的静态字段；
 * 需要判断当前玩家、职业、心情值、任务状态、HUD 位移/透明度时，都从这个上下文读取。
 */
@Environment(EnvType.CLIENT)
public record MoodHudContext(
        PlayerEntity player,
        TextRenderer textRenderer,
        DrawContext drawContext,
        RenderTickCounter tickCounter,
        GameWorldComponent gameWorld,
        PlayerMoodComponent moodComponent,
        Role role,
        float previousMood,
        float moodRender,
        float moodAlpha,
        float moodOffset,
        float moodTextWidth,
        float warningProgress,
        float shakeX,
        float shakeY
) {
    /**
     * 是否存在服务端当前仍然同步在组件里的活跃心情任务。
     *
     * <p>注意它不包含 MoodRenderer 内部的淡出 renderer。
     * 也就是说任务刚完成、文字和心情条还在播放收尾淡出时，这里已经会返回 false。
     * 如果扩展职业希望保留 Wathe 原本“任务完成后先拉回默认长度再淡出”的假心情条动画，
     * 不应该直接用这个方法控制 bar 可见性，而应该让 {@link MoodHudStyle} 使用默认的 HUD alpha 可见条件。
     */
    public boolean hasMoodTasks() {
        return !this.moodComponent.tasks.isEmpty();
    }

    /**
     * 当前心情条应该绘制的像素宽度。
     *
     * <p>Wathe 原版用矩阵缩放 1px 色块。这里改成提供实际宽度，
     * 方便扩展职业绘制多色/流动渐变条，同时视觉结果与原缩放宽度保持一致。
     */
    public int moodBarWidth() {
        return Math.max(0, Math.round(Math.max(1.0F, this.moodTextWidth - 8.0F) * this.moodRender));
    }
}
