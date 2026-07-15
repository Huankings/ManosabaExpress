package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.time.TimeHudApi;
import dev.doctor4t.wathe.cca.GameTimeComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class TimeRenderer {
    public static TimeNumberRenderer view = new TimeNumberRenderer();
    public static float offsetDelta = 0f;
    private static boolean defaultProviderRegistered = false;
    private static Identifier lastSourceId = null;

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context, float delta) {
        ensureDefaultProviderRegistered();
        TimeHudApi.TimeDisplay display = TimeHudApi.resolveDisplay(player);
        if (display.action() != TimeHudApi.TimeDisplay.Action.SHOW) {
            /*
             * 没有 provider 要显示时间时，清掉来源标记。
             * 下一次重新进入游戏或特殊倒计时重新出现时，滚动数字会从新来源干净开始。
             */
            lastSourceId = null;
            return;
        }

        /*
         * 不同 provider 代表不同“时间语义”：普通回合时间、双活倒计时、未来可能的自然增长计时器等。
         * 来源切换时直接重置滚动数字，避免从 8:30 滚到 0:40 这种跨语义动画显得像残留 bug。
         */
        if (!Objects.equals(lastSourceId, display.sourceId())) {
            resetTransientState();
            lastSourceId = display.sourceId();
        }

        int time = display.ticks();
        updateOffsetDelta(display, time, delta);
        view.setTarget(time);

        int colour = getDisplayColour(display);
        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, 6, 0);
        view.render(renderer, context, 0, 0, colour, delta);
        context.getMatrices().pop();
    }

    public static void tick() {
        view.update();
    }

    public static void resetTransientState() {
        /*
         * view 和 offsetDelta 是 TimeRenderer 的滚动数字状态。
         * 扩展 HUD 短暂接管顶部时间后，可以调用这个方法把动画状态清干净，
         * 不需要再直接 new TimeNumberRenderer 或手动改 offsetDelta。
         */
        view = new TimeNumberRenderer();
        offsetDelta = 0f;
    }

    private static void ensureDefaultProviderRegistered() {
        if (defaultProviderRegistered) {
            return;
        }
        defaultProviderRegistered = true;

        TimeHudApi.registerDefaultProvider(Wathe.id("default_game_time"), TimeHudApi.DEFAULT_PRIORITY, viewer -> {
            GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(viewer.getWorld());
            Role role = gameWorldComponent.getRole(viewer);
            boolean canSeeDefaultTime = gameWorldComponent.isRunning()
                    && ((role != null && role.canSeeTime()) || GameFunctions.isPlayerSpectatingOrCreative(viewer));
            if (!canSeeDefaultTime) {
                return TimeHudApi.TimeDisplay.pass();
            }

            /*
             * Wathe 原生时间仍然是倒计时，所以保留“低于 1 分钟强制偏红”的紧迫感。
             * 其它扩展如果注册自然增长时间，可以返回 showDynamic(..., NO_LOW_TIME_WARNING, ...)
             * 来关闭这条倒计时专属规则。
             */
            int time = GameTimeComponent.KEY.get(viewer.getWorld()).getTime();
            return TimeHudApi.TimeDisplay.showCountdown(time, GameConstants.getInTicks(1, 0));
        });
    }

    private static void updateOffsetDelta(@NotNull TimeHudApi.TimeDisplay display, int time, float delta) {
        int threshold = display.changeFlashThreshold();
        if (Math.abs(view.getTarget() - time) > threshold) {
            /*
             * 目标时间比当前目标大：偏绿，表达“时间增加”。
             * 目标时间更小：偏红，表达“时间减少”。这同时兼容击杀加时和自然增长类 provider。
             */
            offsetDelta = time > view.getTarget() ? .6f : -.6f;
        }

        boolean shouldForceLowTimeWarning = display.colorMode() == TimeHudApi.TimeDisplay.ColorMode.DYNAMIC
                && display.lowTimeWarningTicks() >= 0
                && time < display.lowTimeWarningTicks();
        if (shouldForceLowTimeWarning) {
            offsetDelta = -0.9f;
        } else {
            offsetDelta = MathHelper.lerp(delta / 16, offsetDelta, 0f);
        }
    }

    private static int getDisplayColour(@NotNull TimeHudApi.TimeDisplay display) {
        if (display.colorMode() == TimeHudApi.TimeDisplay.ColorMode.FIXED) {
            return withFullAlpha(display.fixedColor());
        }

        float r = offsetDelta > 0 ? 1f - offsetDelta : 1f;
        float g = offsetDelta < 0 ? 1f + offsetDelta : 1f;
        float b = 1f - Math.abs(offsetDelta);
        return MathHelper.packRgb(r, g, b) | 0xFF000000;
    }

    private static int withFullAlpha(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }

    public static class TimeNumberRenderer {
        private final Pair<ScrollingDigit, ScrollingDigit> minutes = new Pair<>(new ScrollingDigit(7200, false), new ScrollingDigit(720, false));
        private final Pair<ScrollingDigit, ScrollingDigit> seconds = new Pair<>(new ScrollingDigit(120, true), new ScrollingDigit(12, false));
        private float target;

        public void setTarget(float target) {
            this.target = target;
            float seconds = target / 20;
            float mins = seconds / 60;
            this.seconds.getLeft().setTarget(seconds / 10);
            this.seconds.getRight().setTarget(seconds);
            this.minutes.getLeft().setTarget(mins / 10);
            this.minutes.getRight().setTarget(mins);
        }

        public void update() {
            this.minutes.getLeft().update();
            this.minutes.getRight().update();
            this.seconds.getLeft().update();
            this.seconds.getRight().update();
        }

        public void render(TextRenderer renderer, @NotNull DrawContext context, int x, int y, int colour, float delta) {
            context.getMatrices().push();
            context.getMatrices().translate(x, y, 0);
            context.getMatrices().translate(16, 0, 0);
            this.seconds.getRight().render(renderer, context, colour, delta);
            context.getMatrices().translate(-8, 0, 0);
            this.seconds.getLeft().render(renderer, context, colour, delta);
            context.getMatrices().translate(-8, 0, 0);
            context.drawTextWithShadow(renderer, ":", 2, 0, colour);
            context.getMatrices().translate(-8, 0, 0);
            this.minutes.getRight().render(renderer, context, colour, delta);
            context.getMatrices().translate(-8, 0, 0);
            this.minutes.getLeft().render(renderer, context, colour, delta);
            context.getMatrices().pop();
        }

        public float getTarget() {
            return this.target;
        }
    }

    public static class ScrollingDigit {
        private final int power;
        private final boolean cap6;
        private float target;
        private float value;
        private float lastValue;

        public ScrollingDigit(int power, boolean cap6) {
            this.power = power;
            this.cap6 = cap6;
        }

        public void update() {
            this.lastValue = this.value;
            this.value = MathHelper.lerp(0.15f, this.value, this.target);
            if (Math.abs(this.value - this.target) < 0.01f) this.value = this.target;
        }

        public void render(@NotNull TextRenderer renderer, @NotNull DrawContext context, int colour, float delta) {
            float value = MathHelper.lerp(delta, this.lastValue, this.value);
            int digit = MathHelper.floor(value) % (this.cap6 ? 6 : 10);
            int digitNext = MathHelper.floor(value + 1) % (this.cap6 ? 6 : 10);
            double offset = Math.pow(value % 1, this.power);
            colour &= 0xFFFFFF;
            context.getMatrices().push();
            context.getMatrices().translate(0, -offset * (renderer.fontHeight + 2), 0);
            double alpha = (1.0f - Math.abs(offset)) * 255.0f;
            int baseColour = colour | (int) alpha << 24;
            int nextColour = colour | (int) (Math.abs(offset) * 255.0f) << 24;
            if ((baseColour & -67108864) != 0)
                context.drawTextWithShadow(renderer, String.valueOf(digit), 0, 0, baseColour);
            if ((nextColour & -67108864) != 0)
                context.drawTextWithShadow(renderer, String.valueOf(digitNext), 0, renderer.fontHeight + 2, nextColour);
            context.getMatrices().pop();
        }

        public void setTarget(float target) {
            this.target = target;
        }
    }
}
