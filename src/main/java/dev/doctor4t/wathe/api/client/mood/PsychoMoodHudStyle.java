package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Psycho mode 左侧图标的可替换样式。
 *
 * <p>目前只开放普通身体、受击身体、眼睛三张 sprite。
 * 文本、倒计时条、残影抖动继续由 Wathe 统一绘制，扩展职业只需要返回自己的贴图即可。
 */
@Environment(EnvType.CLIENT)
public final class PsychoMoodHudStyle {
    public static final Identifier DEFAULT_BODY = Wathe.id("hud/mood_psycho");
    public static final Identifier DEFAULT_HIT_BODY = Wathe.id("hud/mood_psycho_hit");
    public static final Identifier DEFAULT_EYES = Wathe.id("hud/mood_psycho_eyes");

    private final SpriteProvider bodyProvider;
    private final SpriteProvider hitBodyProvider;
    private final SpriteProvider eyesProvider;

    public PsychoMoodHudStyle(SpriteProvider bodyProvider, SpriteProvider hitBodyProvider, SpriteProvider eyesProvider) {
        this.bodyProvider = Objects.requireNonNull(bodyProvider, "bodyProvider");
        this.hitBodyProvider = Objects.requireNonNull(hitBodyProvider, "hitBodyProvider");
        this.eyesProvider = Objects.requireNonNull(eyesProvider, "eyesProvider");
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

    @FunctionalInterface
    public interface SpriteProvider {
        @Nullable Identifier getSprite(MoodHudContext context, PlayerPsychoComponent psycho);
    }
}
