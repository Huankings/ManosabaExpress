package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.api.shop.ShopApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StoreRenderer {
    private static final int HUD_RIGHT_MARGIN = 12;
    private static final int HUD_TOP = 6;
    private static final int HUD_LINE_HEIGHT = 10;
    private static final float HUD_OFFSET_LERP_DIVISOR = 8f;
    private static final float HUD_ALPHA_LERP_DIVISOR = 12f;
    private static final float HUD_CHANGE_FLASH = 0.6f;
    private static final float HUD_CHANGE_FADE_DIVISOR = 16f;

    /**
     * 旧字段保留给可能 shadow 过 StoreRenderer 的扩展模组。
     * 新版 HUD 已经改成按货币 ID 拆分的 renderers 映射，不再直接读取这两个字段。
     */
    @Deprecated
    public static final MoneyNumberRenderer view = new MoneyNumberRenderer();
    @Deprecated
    public static float offsetDelta = 0f;

    private static final Map<Identifier, CurrencyLineRenderer> renderers = new HashMap<>();

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context, float delta) {
        /*
         * HUD 每帧从 EconomyApi 拿“当前应该显示的非零货币余额”。
         * 这样新增货币只需要注册定义和 HUD 判定，不用再 mixin 到 StoreRenderer。
         */
        List<EconomyApi.CurrencyBalance> balances = EconomyApi.getVisibleCurrencyBalances(player, ShopApi.hasShop(player));
        Set<Identifier> present = new HashSet<>();
        for (int i = 0; i < balances.size(); i++) {
            EconomyApi.CurrencyBalance balance = balances.get(i);
            Identifier currencyId = balance.currency().id();
            present.add(currencyId);
            CurrencyLineRenderer line = renderers.computeIfAbsent(currencyId, id -> new CurrencyLineRenderer(balance.currency().icon()));
            line.tickVisible(balance.amount(), i, delta);
        }

        /*
         * EconomyApi 会把余额为 0 的货币从 balances 中过滤掉。
         * 旧逻辑在这里直接 remove，会导致“刚好花到 0”的金币整行瞬间消失；
         * 新逻辑让已经存在的 HUD 行先把目标数值滚动到 0，再淡出移除。
         *
         * 排列上把这些退出中的货币放到当前可见货币之后，相当于按 0 余额排在最下面。
         * 如果之后它又重新获得余额，tickVisible 会取消退出状态并恢复正常显示。
         */
        ArrayList<CurrencyLineRenderer> exiting = new ArrayList<>();
        for (Map.Entry<Identifier, CurrencyLineRenderer> entry : renderers.entrySet()) {
            if (!present.contains(entry.getKey())) {
                exiting.add(entry.getValue());
            }
        }
        exiting.sort((a, b) -> Float.compare(a.offset, b.offset));
        for (int i = 0; i < exiting.size(); i++) {
            exiting.get(i).tickExiting(balances.size() + i, delta);
        }
        renderers.entrySet().removeIf(entry -> entry.getValue().isGone());
        if (renderers.isEmpty()) {
            return;
        }

        ArrayList<CurrencyLineRenderer> ordered = new ArrayList<>(renderers.values());
        ordered.sort((a, b) -> Float.compare(a.offset, b.offset));
        for (CurrencyLineRenderer line : ordered) {
            context.getMatrices().push();
            context.getMatrices().translate(context.getScaledWindowWidth() - HUD_RIGHT_MARGIN, HUD_TOP + HUD_LINE_HEIGHT * line.offset, 0);
            line.render(renderer, context, delta);
            context.getMatrices().pop();
        }
    }

    public static void tick() {
        for (CurrencyLineRenderer renderer : renderers.values()) {
            renderer.view.update();
        }
    }

    private static class CurrencyLineRenderer {
        private final MoneyNumberRenderer view = new MoneyNumberRenderer();
        private final String icon;
        private float offset = 0f;
        private float alpha = 0.075f;
        private float offsetDelta = 0f;
        private boolean exiting = false;

        private CurrencyLineRenderer(@NotNull String icon) {
            this.icon = icon;
        }

        private void tickVisible(int amount, int index, float delta) {
            this.exiting = false;
            this.tickAmount(amount);
            this.offset = MathHelper.lerp(delta / HUD_OFFSET_LERP_DIVISOR, this.offset, index);
            this.alpha = MathHelper.lerp(delta / HUD_ALPHA_LERP_DIVISOR, this.alpha, 1f);
            this.offsetDelta = MathHelper.lerp(delta / HUD_CHANGE_FADE_DIVISOR, this.offsetDelta, 0f);
        }

        private void tickExiting(int index, float delta) {
            this.exiting = true;
            this.tickAmount(0);
            this.offset = MathHelper.lerp(delta / HUD_OFFSET_LERP_DIVISOR, this.offset, index);

            /*
             * 归零时先保持 HUD 可见，让滚动数字按原动效走到 0。
             * 数字完全停在 0 后才开始淡出，避免消费到 0 时图标和数字瞬间消失。
             */
            float targetAlpha = this.view.isSettled() ? 0f : 1f;
            this.alpha = MathHelper.lerp(delta / HUD_ALPHA_LERP_DIVISOR, this.alpha, targetAlpha);
            this.offsetDelta = MathHelper.lerp(delta / HUD_CHANGE_FADE_DIVISOR, this.offsetDelta, 0f);
        }

        private void tickAmount(int amount) {
            if (this.view.getTarget() != amount) {
                this.offsetDelta = amount > this.view.getTarget() ? HUD_CHANGE_FLASH : -HUD_CHANGE_FLASH;
                this.view.setTarget(amount);
            }
        }

        private boolean isGone() {
            return this.exiting && this.view.isSettled() && this.alpha < 0.02f;
        }

        private void render(TextRenderer renderer, @NotNull DrawContext context, float delta) {
            float r = this.offsetDelta > 0 ? 1f - this.offsetDelta : 1f;
            float g = this.offsetDelta < 0 ? 1f + this.offsetDelta : 1f;
            float b = 1f - Math.abs(this.offsetDelta);
            int alpha = MathHelper.clamp((int) (this.alpha * 255), 0, 255);
            int colour = (MathHelper.packRgb(r, g, b) & 0xFFFFFF) | (alpha << 24);
            this.view.render(renderer, context, this.icon, 0, 0, colour, delta);
        }
    }

    public static class MoneyNumberRenderer {
        private static final int DIGIT_SPACING = 8;

        private final List<ScrollingDigit> digits = new ArrayList<>();
        private int target;

        public void setTarget(int target) {
            this.target = target;
            int length = String.valueOf(Math.max(0, target)).length();
            while (this.digits.size() < length) this.digits.add(new ScrollingDigit(this.digits.isEmpty()));
            for (int i = 0; i < this.digits.size(); i++) {
                if (i == 0) {
                    this.digits.get(i).setTarget((float) (target / Math.pow(10, i)));
                } else {
                    this.digits.get(i).setTarget((int) (target / Math.pow(10, i)));
                }
            }
        }

        public void update() {
            for (ScrollingDigit digit : this.digits) digit.update();
        }

        public boolean isSettled() {
            for (ScrollingDigit digit : this.digits) {
                if (!digit.isSettled()) {
                    return false;
                }
            }
            return true;
        }

        public void render(TextRenderer renderer, @NotNull DrawContext context, @NotNull String icon, int x, int y, int colour, float delta) {
            context.getMatrices().push();
            context.getMatrices().translate(x, y, 0);
            context.drawTextWithShadow(renderer, icon, 0, 0, colour);
            int offset = -DIGIT_SPACING;
            for (ScrollingDigit digit : this.digits) {
                context.getMatrices().push();
                context.getMatrices().translate(offset, 0, 0);
                digit.render(renderer, context, colour, delta);
                offset -= DIGIT_SPACING;
                context.getMatrices().pop();
            }
            context.getMatrices().pop();
        }

        public int getTarget() {
            return this.target;
        }
    }

    public static class ScrollingDigit {
        private final boolean force;
        private float target;
        private float value;
        private float lastValue;

        public ScrollingDigit(boolean force) {
            this.force = force;
        }

        public void update() {
            this.lastValue = this.value;
            this.value = MathHelper.lerp(0.15f, this.value, this.target);
            if (Math.abs(this.value - this.target) < 0.01f) this.value = this.target;
        }

        public void render(@NotNull TextRenderer renderer, @NotNull DrawContext context, int colour, float delta) {
            if (MathHelper.floor(this.lastValue) != MathHelper.floor(this.value)) {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
//                if (player != null)player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.BALANCE_CLICK, SoundCategory.PLAYERS, 0.1f, 1 + this.lastValue - this.value, player.getRandom().nextLong());
            }
            float value = MathHelper.lerp(delta, this.lastValue, this.value);
            int digit = MathHelper.floor(value) % 10;
            int digitNext = MathHelper.floor(value + 1) % 10;
            float offset = value % 1;
            colour &= 0xFFFFFF;
            context.getMatrices().push();
            context.getMatrices().translate(0, -offset * (renderer.fontHeight + 2), 0);
            float alpha = (1.0f - Math.abs(offset)) * 255.0f;
            if (value < 1 && !this.force) alpha *= value;
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

        public boolean isSettled() {
            return Math.abs(this.value - this.target) < 0.01f && Math.abs(this.lastValue - this.target) < 0.01f;
        }
    }
}
