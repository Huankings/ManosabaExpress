package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.Wathe;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 一个职业的普通心情 HUD 样式。
 *
 * <p>这里刻意只描述“画什么”和“心情条怎么画”，而不暴露 Wathe 内部动画状态写法。
 * 位移、透明度、警告抖动、箭头进出场这些公共行为仍由 Wathe 统一处理。
 */
@Environment(EnvType.CLIENT)
public final class MoodHudStyle {
    private static final Identifier DEFAULT_ARROW_UP = Wathe.id("hud/arrow_up");
    private static final Identifier DEFAULT_ARROW_DOWN = Wathe.id("hud/arrow_down");

    private final SpriteProvider spriteProvider;
    private final SpriteProvider arrowUpProvider;
    private final SpriteProvider arrowDownProvider;
    private final IconRenderer iconRenderer;
    private final OverlayProvider overlayProvider;
    private final BarRenderer barRenderer;
    private final BarVisibility barVisibility;
    private final boolean renderArrows;
    private final boolean renderWarningText;

    private MoodHudStyle(Builder builder) {
        this.spriteProvider = Objects.requireNonNull(builder.spriteProvider, "spriteProvider");
        this.arrowUpProvider = Objects.requireNonNull(builder.arrowUpProvider, "arrowUpProvider");
        this.arrowDownProvider = Objects.requireNonNull(builder.arrowDownProvider, "arrowDownProvider");
        this.iconRenderer = builder.iconRenderer;
        this.overlayProvider = Objects.requireNonNull(builder.overlayProvider, "overlayProvider");
        this.barRenderer = builder.barRenderer;
        this.barVisibility = Objects.requireNonNull(builder.barVisibility, "barVisibility");
        this.renderArrows = builder.renderArrows;
        this.renderWarningText = builder.renderWarningText;
    }

    public static Builder builder(SpriteProvider spriteProvider) {
        return new Builder(spriteProvider);
    }

    public static Builder builder(Identifier sprite) {
        return new Builder(context -> sprite);
    }

    public @Nullable Identifier sprite(MoodHudContext context) {
        return this.spriteProvider.getSprite(context);
    }

    public @Nullable Identifier arrowUp(MoodHudContext context) {
        return this.arrowUpProvider.getSprite(context);
    }

    public @Nullable Identifier arrowDown(MoodHudContext context) {
        return this.arrowDownProvider.getSprite(context);
    }

    public List<Identifier> overlays(MoodHudContext context) {
        return this.overlayProvider.getOverlays(context);
    }

    public @Nullable IconRenderer iconRenderer() {
        return this.iconRenderer;
    }

    public @Nullable BarRenderer barRenderer() {
        return this.barRenderer;
    }

    public boolean shouldRenderBar(MoodHudContext context) {
        return this.barRenderer != null && this.barVisibility.shouldRender(context);
    }

    public boolean renderArrows() {
        return this.renderArrows;
    }

    public boolean renderWarningText() {
        return this.renderWarningText;
    }

    private static int withHudAlpha(int rgbOrArgb, float alpha) {
        /*
         * 这里故意兼容两种颜色输入：
         * 1. Wathe 本体和多数扩展使用 0xRRGGBB；
         * 2. 有些扩展会直接传 java.awt.Color#getRGB()，它返回的是 0xAARRGGBB，
         *    默认 alpha 高字节就是 0xFF。
         *
         * 心情条是否淡出应该完全由 MoodRenderer 计算出来的 HUD alpha 决定。
         * 如果保留输入颜色自带的 alpha，再用 bitwise OR 叠加 HUD alpha，
         * 那么 0xFFxxxxxx 会把淡出中的 0 alpha 重新顶回不透明，导致假心情条任务完成后留在屏幕上。
         */
        int rgb = rgbOrArgb & 0x00FFFFFF;
        int alphaByte = MathHelper.floor(MathHelper.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        return rgb | (alphaByte << 24);
    }

    @FunctionalInterface
    public interface SpriteProvider {
        @Nullable Identifier getSprite(MoodHudContext context);
    }

    @FunctionalInterface
    public interface OverlayProvider {
        List<Identifier> getOverlays(MoodHudContext context);
    }

    @FunctionalInterface
    public interface IconRenderer {
        void render(MoodHudContext context);
    }

    @FunctionalInterface
    public interface BarRenderer {
        void render(MoodHudContext context, int width, float alpha);
    }

    @FunctionalInterface
    public interface BarVisibility {
        boolean shouldRender(MoodHudContext context);
    }

    public static final class Builder {
        private final SpriteProvider spriteProvider;
        private SpriteProvider arrowUpProvider = context -> DEFAULT_ARROW_UP;
        private SpriteProvider arrowDownProvider = context -> DEFAULT_ARROW_DOWN;
        private IconRenderer iconRenderer = null;
        private OverlayProvider overlayProvider = context -> List.of();
        private BarRenderer barRenderer = null;
        private BarVisibility barVisibility = context -> context.moodAlpha() > 0.0F;
        private boolean renderArrows = false;
        private boolean renderWarningText = true;

        private Builder(SpriteProvider spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        public Builder arrows() {
            this.renderArrows = true;
            return this;
        }

        public Builder arrows(Identifier arrowUp, Identifier arrowDown) {
            this.arrowUpProvider = context -> arrowUp;
            this.arrowDownProvider = context -> arrowDown;
            this.renderArrows = true;
            return this;
        }

        public Builder arrows(SpriteProvider arrowUpProvider, SpriteProvider arrowDownProvider) {
            this.arrowUpProvider = arrowUpProvider;
            this.arrowDownProvider = arrowDownProvider;
            this.renderArrows = true;
            return this;
        }

        public Builder overlays(OverlayProvider overlayProvider) {
            this.overlayProvider = overlayProvider;
            return this;
        }

        public Builder icon(IconRenderer iconRenderer) {
            this.iconRenderer = iconRenderer;
            return this;
        }

        public Builder barColor(int rgb) {
            this.barRenderer = (context, width, alpha) -> {
                if (width <= 0 || alpha <= 0.0F) {
                    return;
                }
                context.drawContext().fill(0, 0, width, 1, withHudAlpha(rgb, alpha));
            };
            return this;
        }

        public Builder hsvMoodBar() {
            this.barRenderer = (context, width, alpha) -> {
                if (width <= 0 || alpha <= 0.0F) {
                    return;
                }
                int colour = MathHelper.hsvToRgb(context.moodRender() / 3.0F, 1.0F, 1.0F);
                context.drawContext().fill(0, 0, width, 1, withHudAlpha(colour, alpha));
            };
            return this;
        }

        public Builder bar(BarRenderer barRenderer) {
            this.barRenderer = barRenderer;
            return this;
        }

        public Builder barVisibleWhen(BarVisibility barVisibility) {
            this.barVisibility = barVisibility;
            return this;
        }

        public Builder hideWarningText() {
            this.renderWarningText = false;
            return this;
        }

        public MoodHudStyle build() {
            return new MoodHudStyle(this);
        }
    }
}
