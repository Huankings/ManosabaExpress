package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Psycho mode 左侧图标的可替换样式。
 *
 * <p>目前开放普通身体、受击/破损身体、眼睛三张 sprite，以及疯魔文本、
 * 文本颜色和倒计时条颜色。
 * 残影、抖动、跑马灯和倒计时条位置继续由 Wathe 统一绘制，扩展职业只需要返回自己的内容/颜色/贴图即可。
 * Wathe 会按当前疯魔护盾层数选择身体贴图：护盾仍大于 0 时使用普通身体，
 * 护盾为 0 时使用受击/破损身体。因此 0 护盾 profile 从启动开始就会显示破损态。
 */
@Environment(EnvType.CLIENT)
public final class PsychoMoodHudStyle {
    public static final Identifier DEFAULT_BODY = Wathe.id("hud/mood_psycho");
    public static final Identifier DEFAULT_HIT_BODY = Wathe.id("hud/mood_psycho_hit");
    public static final Identifier DEFAULT_EYES = Wathe.id("hud/mood_psycho_eyes");
    public static final int DEFAULT_TEXT_COLOUR = MathHelper.hsvToRgb(0F, 1.0F, 0.5F);
    public static final int DEFAULT_TIMER_BAR_COLOUR = DEFAULT_TEXT_COLOUR;

    private final SpriteProvider bodyProvider;
    private final SpriteProvider hitBodyProvider;
    private final SpriteProvider eyesProvider;
    private final TextProvider textProvider;
    private final ColourProvider textColourProvider;
    private final ColourProvider timerBarColourProvider;

    public PsychoMoodHudStyle(SpriteProvider bodyProvider, SpriteProvider hitBodyProvider, SpriteProvider eyesProvider) {
        this(
                bodyProvider,
                hitBodyProvider,
                eyesProvider,
                (context, psycho) -> Text.translatable("game.psycho_mode.text"),
                (context, psycho) -> DEFAULT_TEXT_COLOUR,
                (context, psycho) -> DEFAULT_TIMER_BAR_COLOUR
        );
    }

    public PsychoMoodHudStyle(
            SpriteProvider bodyProvider,
            SpriteProvider hitBodyProvider,
            SpriteProvider eyesProvider,
            TextProvider textProvider,
            ColourProvider textColourProvider,
            ColourProvider timerBarColourProvider
    ) {
        this.bodyProvider = Objects.requireNonNull(bodyProvider, "bodyProvider");
        this.hitBodyProvider = Objects.requireNonNull(hitBodyProvider, "hitBodyProvider");
        this.eyesProvider = Objects.requireNonNull(eyesProvider, "eyesProvider");
        this.textProvider = Objects.requireNonNull(textProvider, "textProvider");
        this.textColourProvider = Objects.requireNonNull(textColourProvider, "textColourProvider");
        this.timerBarColourProvider = Objects.requireNonNull(timerBarColourProvider, "timerBarColourProvider");
    }

    public static PsychoMoodHudStyle defaults() {
        return new PsychoMoodHudStyle(
                (context, psycho) -> DEFAULT_BODY,
                (context, psycho) -> DEFAULT_HIT_BODY,
                (context, psycho) -> DEFAULT_EYES
        );
    }

    public @Nullable Identifier body(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.bodyProvider.getSprite(context, psycho);
    }

    public @Nullable Identifier hitBody(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.hitBodyProvider.getSprite(context, psycho);
    }

    public @Nullable Identifier eyes(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.eyesProvider.getSprite(context, psycho);
    }

    public @Nullable Text text(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.textProvider.getText(context, psycho);
    }

    public int textColour(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.textColourProvider.getColour(context, psycho);
    }

    public int timerBarColour(MoodHudContext context, PlayerPsychoComponent psycho) {
        return this.timerBarColourProvider.getColour(context, psycho);
    }

    @FunctionalInterface
    public interface SpriteProvider {
        @Nullable Identifier getSprite(MoodHudContext context, PlayerPsychoComponent psycho);
    }

    @FunctionalInterface
    public interface TextProvider {
        @Nullable Text getText(MoodHudContext context, PlayerPsychoComponent psycho);
    }

    @FunctionalInterface
    public interface ColourProvider {
        /**
         * 返回 0xRRGGBB 或 0xAARRGGBB 均可；MoodRenderer 会重新写入本次 HUD 所需的 alpha。
         */
        int getColour(MoodHudContext context, PlayerPsychoComponent psycho);
    }
}
