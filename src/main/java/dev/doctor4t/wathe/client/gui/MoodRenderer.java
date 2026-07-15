package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.mood.MoodHudApi;
import dev.doctor4t.wathe.api.client.mood.MoodHudContext;
import dev.doctor4t.wathe.api.client.mood.MoodHudStyle;
import dev.doctor4t.wathe.api.client.mood.PsychoMoodHudStyle;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.game.GameConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MoodRenderer {
    public static final Identifier ARROW_UP = Wathe.id("hud/arrow_up");
    public static final Identifier ARROW_DOWN = Wathe.id("hud/arrow_down");
    public static final Identifier MOOD_HAPPY = Wathe.id("hud/mood_happy");
    public static final Identifier MOOD_MID = Wathe.id("hud/mood_mid");
    public static final Identifier MOOD_DEPRESSIVE = Wathe.id("hud/mood_depressive");
    public static final Identifier MOOD_KILLER = Wathe.id("hud/mood_killer");
    public static final Identifier MOOD_PSYCHO = Wathe.id("hud/mood_psycho");
    public static final Identifier MOOD_PSYCHO_HIT = Wathe.id("hud/mood_psycho_hit");
    public static final Identifier MOOD_PSYCHO_EYES = Wathe.id("hud/mood_psycho_eyes");

    private static final Map<PlayerMoodComponent.Task, TaskRenderer> renderers = new HashMap<>();
    public static final Random random = new Random();
    public static float arrowProgress = 1f;
    public static float moodRender = 0f;
    public static float moodOffset = 0f;
    public static float moodTextWidth = 0f;
    public static float moodAlpha = 0f;
    /**
     * 下面这几个渲染期静态字段专门用于兼容旧扩展模组：
     * 1. 让 renderHud 仍然可以调用旧签名的 renderCivilian(textRenderer, context, oldMood)；
     * 2. 同时又不丢掉新的警告阈值、抖动和警告文字逻辑。
     *
     * <p>starryexpress 会在 renderHud 里精确寻找一次对旧 renderCivilian 签名的调用并注入。
     * 因此这里把新增的 warningProgress / shake 状态先缓存成静态字段，再由旧签名方法读取。
     */
    private static float currentWarningProgress = 0f;
    private static float currentShakeX = 0f;
    private static float currentShakeY = 0f;
    private static MoodHudContext currentMoodHudContext = null;

    private static final MoodHudStyle DEFAULT_REAL_STYLE = MoodHudStyle
            .builder(MoodRenderer::getDefaultRealMoodSprite)
            .arrows()
            .hsvMoodBar()
            .build();
    private static final MoodHudStyle DEFAULT_FAKE_STYLE = MoodHudStyle
            .builder(MOOD_KILLER)
            .barColor(MathHelper.hsvToRgb(0F, 1.0F, 0.6F))
            .build();

    @Environment(EnvType.CLIENT)
    public static void renderHud(@NotNull PlayerEntity player, TextRenderer textRenderer, DrawContext context, RenderTickCounter tickCounter) {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(player.getWorld());
        if (!gameWorldComponent.isRunning() || !WatheClient.isPlayerAliveAndInSurvival() || !MoodHudApi.shouldRenderInGameMode(gameWorldComponent.getGameMode())) {
            return;
        }

        PlayerMoodComponent component = PlayerMoodComponent.KEY.get(player);
        PlayerPsychoComponent psycho = PlayerPsychoComponent.KEY.get(player);
        Role role = gameWorldComponent.getRole(player);
        boolean isFakeMood = role != null && role.getMoodType() == Role.MoodType.FAKE;
        boolean isRealMood = role != null && role.getMoodType() == Role.MoodType.REAL;
        float warningProgress = getMoodWarningProgress(component, gameWorldComponent, role);
        float[] warningShake = getWarningShake(player, warningProgress);
        currentWarningProgress = warningProgress;
        currentShakeX = warningShake[0];
        currentShakeY = warningShake[1];

        float oldMood = moodRender;
        moodRender = MathHelper.lerp(tickCounter.getTickDelta(true) / 8, moodRender, component.getMood());
        moodAlpha = MathHelper.lerp(
                tickCounter.getTickDelta(true) / 16,
                moodAlpha,
                (renderers.isEmpty() && warningProgress <= 0f) ? 0f : 1f
        );

        /*
         * 从这里开始，本帧的 HUD 状态已经全部算好。
         * 新 API 只把这些状态作为只读上下文交给扩展职业，由 Wathe 继续统一处理位置、
         * 透明度、警告抖动和箭头动画，避免扩展再 shadow 私有字段或复制整段渲染逻辑。
         */
        currentMoodHudContext = new MoodHudContext(
                player,
                textRenderer,
                context,
                tickCounter,
                gameWorldComponent,
                component,
                role,
                oldMood,
                moodRender,
                moodAlpha,
                moodOffset,
                moodTextWidth,
                currentWarningProgress,
                currentShakeX,
                currentShakeY
        );

        if (psycho.getPsychoTicks() > 0) {
            renderPsycho(player, textRenderer, context, psycho, tickCounter, currentMoodHudContext);
            return;
        }

        for (PlayerMoodComponent.Task task : component.tasks.keySet()) {
            if (!renderers.containsKey(task)) {
                for (TaskRenderer renderer : renderers.values()) {
                    renderer.index++;
                }
                renderers.put(task, new TaskRenderer());
            }
        }

        ArrayList<PlayerMoodComponent.Task> toRemove = new ArrayList<>();
        for (PlayerMoodComponent.Task taskType : PlayerMoodComponent.Task.values()) {
            TaskRenderer task = renderers.get(taskType);
            if (task != null) {
                task.present = false;
                if (task.tick(component.tasks.get(taskType), tickCounter.getTickDelta(true), isFakeMood)) {
                    toRemove.add(taskType);
                }
            }
        }

        for (PlayerMoodComponent.Task task : toRemove) {
            renderers.remove(task);
        }

        if (!toRemove.isEmpty()) {
            ArrayList<TaskRenderer> renderersList = new ArrayList<>(renderers.values());
            renderersList.sort((a, b) -> Float.compare(a.offset, b.offset));
            for (int i = 0; i < renderersList.size(); i++) {
                renderersList.get(i).index = i;
            }
        }

        TaskRenderer maxRenderer = null;
        for (TaskRenderer renderer : renderers.values()) {
            if (maxRenderer == null || renderer.offset > maxRenderer.offset) {
                maxRenderer = renderer;
            }
        }

        if (maxRenderer != null) {
            moodOffset = MathHelper.lerp(tickCounter.getTickDelta(true) / 8, moodOffset, maxRenderer.offset);
            moodTextWidth = MathHelper.lerp(tickCounter.getTickDelta(true) / 32, moodTextWidth, textRenderer.getWidth(maxRenderer.text));
        } else {
            moodOffset = MathHelper.lerp(tickCounter.getTickDelta(true) / 8, moodOffset, 0f);
            moodTextWidth = MathHelper.lerp(tickCounter.getTickDelta(true) / 32, moodTextWidth, 100f);
        }

        currentMoodHudContext = new MoodHudContext(
                player,
                textRenderer,
                context,
                tickCounter,
                gameWorldComponent,
                component,
                role,
                oldMood,
                moodRender,
                moodAlpha,
                moodOffset,
                moodTextWidth,
                currentWarningProgress,
                currentShakeX,
                currentShakeY
        );

        for (TaskRenderer renderer : renderers.values()) {
            context.getMatrices().push();
            context.getMatrices().translate(currentShakeX, currentShakeY, 0);
            context.getMatrices().translate(0, 10 * renderer.offset, 0);
            context.drawTextWithShadow(
                    textRenderer,
                    renderer.text,
                    22,
                    6,
                    MathHelper.packRgb(1f, 1f, 1f) | ((int) (renderer.alpha * 255) << 24)
            );
            context.getMatrices().pop();
        }

        if (role != null) {
            MoodHudStyle customStyle = MoodHudApi.resolveMoodStyle(currentMoodHudContext);
            if (customStyle != null) {
                renderMoodStyle(currentMoodHudContext, customStyle);
            } else if (isFakeMood) {
                renderKiller(textRenderer, context);
            } else if (isRealMood) {
                // 这里继续保留旧签名调用，专门兼容 starryexpress 对 renderHud 的精确注入。
                renderCivilian(textRenderer, context, oldMood);
            }
        }

        arrowProgress = MathHelper.lerp(tickCounter.getTickDelta(true) / 24, arrowProgress, 0f);
    }

    private static Identifier getDefaultRealMoodSprite(MoodHudContext context) {
        if (context.moodRender() < GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
            return MOOD_DEPRESSIVE;
        }
        if (context.moodRender() < GameConstants.MID_MOOD_THRESHOLD) {
            return MOOD_MID;
        }
        return MOOD_HAPPY;
    }

    /**
     * 只有在以下条件同时满足时才会进入濒死警告：
     * 1. 当前角色是真实心情角色；
     * 2. 心情死亡机制指令处于开启状态；
     * 3. 当前心情已经跌到警告阈值以内。
     *
     * @return 0~1 的警告强度，越接近 1 说明越接近精神崩溃。
     */
    private static float getMoodWarningProgress(@NotNull PlayerMoodComponent component, @NotNull GameWorldComponent gameWorldComponent, @Nullable Role role) {
        if (role == null || role.getMoodType() != Role.MoodType.REAL || !gameWorldComponent.isMoodEffectDeathEnabled()) {
            return 0f;
        }

        float mood = component.getMood();
        if (!GameConstants.shouldShowMoodBreakdownWarning(mood)) {
            return 0f;
        }

        return MathHelper.clamp(
                (GameConstants.MOOD_BREAKDOWN_WARNING_THRESHOLD - mood) / GameConstants.MOOD_BREAKDOWN_WARNING_THRESHOLD,
                0f,
                1f
        );
    }

    /**
     * 警告阶段所有与心情相关的 HUD 都会统一抖动。
     * 使用玩家年龄做随机种子，保证同一 tick 内整体抖动一致，不会文字和图标各抖各的。
     */
    private static float[] getWarningShake(@NotNull PlayerEntity player, float warningProgress) {
        if (warningProgress <= 0f) {
            return new float[]{0f, 0f};
        }

        random.setSeed(player.age * 31L + 17L);
        float amplitude = 0.35f + warningProgress * 1.65f;
        float shakeX = (random.nextFloat() - 0.5f) * 2f * amplitude;
        float shakeY = (random.nextFloat() - 0.5f) * 2f * amplitude;
        return new float[]{shakeX, shakeY};
    }

    /**
     * 保留旧版 renderCivilian 签名，给扩展模组提供稳定注入点。
     * 新增的 warningProgress 等状态则通过当前帧缓存字段读取。
     */
    private static void renderCivilian(@NotNull TextRenderer textRenderer, @NotNull DrawContext context, float prevMood) {
        if (currentMoodHudContext != null) {
            renderMoodStyle(currentMoodHudContext, DEFAULT_REAL_STYLE);
            return;
        }

        context.getMatrices().push();
        context.getMatrices().translate(currentShakeX, currentShakeY, 0);
        context.getMatrices().translate(0, 3 * moodOffset, 0);

        Identifier mood = MOOD_HAPPY;
        if (moodRender < GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
            mood = MOOD_DEPRESSIVE;
        } else if (moodRender < GameConstants.MID_MOOD_THRESHOLD) {
            mood = MOOD_MID;
        }

        if (arrowProgress < 0.1f) {
            if (prevMood >= GameConstants.DEPRESSIVE_MOOD_THRESHOLD && moodRender < GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
                arrowProgress = -1f;
            } else if (prevMood >= GameConstants.MID_MOOD_THRESHOLD && moodRender < GameConstants.MID_MOOD_THRESHOLD) {
                arrowProgress = -1f;
            }
        }

        context.drawGuiTexture(mood, 5, 6, 14, 17);
        if (Math.abs(arrowProgress) > 0.01f) {
            boolean up = arrowProgress > 0;
            Identifier arrow = up ? ARROW_UP : ARROW_DOWN;
            context.getMatrices().push();
            if (!up) {
                context.getMatrices().translate(0, 4, 0);
            }
            context.getMatrices().translate(0, arrowProgress * 4, 0);
            context.drawSprite(7, 6, 0, 10, 13, context.guiAtlasManager.getSprite(arrow), 1f, 1f, 1f, (float) Math.sin(Math.abs(arrowProgress) * Math.PI));
            context.getMatrices().pop();
        }
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(currentShakeX, currentShakeY, 0);
        context.getMatrices().translate(0, 10 * moodOffset, 0);
        context.getMatrices().translate(26, 8 + textRenderer.fontHeight, 0);
        context.getMatrices().scale(Math.max(1f, moodTextWidth - 8) * moodRender, 1, 1);
        context.fill(0, 0, 1, 1, MathHelper.hsvToRgb(moodRender / 3.0F, 1.0F, 1.0F) | ((int) (moodAlpha * 255) << 24));
        context.getMatrices().pop();

        if (currentWarningProgress > 0f) {
            int pulseColour = getWarningColour(currentWarningProgress);
            /**
             * 警告文字必须和心情条使用同一套位移基准：
             * 1. 先吃到与心情 HUD 一致的抖动偏移；
             * 2. 再吃到 moodOffset 带来的整体下移；
             * 3. 最后在“心情条的下方”用局部坐标绘制。
             *
             * 这样无论任务栏把心情 HUD 顶到哪里，警告文字都会跟着走，
             * 不会再固定停留在最初位置，也不会被上方任务文本覆盖。
             */
            int warningTextY = 12 + textRenderer.fontHeight;
            context.getMatrices().push();
            context.getMatrices().translate(currentShakeX, currentShakeY, 0);
            context.getMatrices().translate(0, 10 * moodOffset, 0);
            context.drawTextWithShadow(
                    textRenderer,
                    Text.translatable("hud.mood.breakdown_warning"),
                    22,
                    warningTextY,
                    pulseColour | ((int) (moodAlpha * 255) << 24)
            );
            context.getMatrices().pop();
        }
    }

    /**
     * 警告文字颜色会随着接近崩溃逐渐从偏橙红过渡到深红，并带一点闪烁。
     */
    private static int getWarningColour(float warningProgress) {
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 90.0) * 0.5f + 0.5f);
        float hue = MathHelper.lerp(warningProgress, 0.08f, 0.0f);
        float saturation = MathHelper.lerp(warningProgress, 0.75f, 1.0f);
        float brightness = MathHelper.lerp(warningProgress, 0.95f - pulse * 0.1f, 0.65f + pulse * 0.35f);
        return MathHelper.hsvToRgb(hue, saturation, brightness);
    }

    private static void renderKiller(@NotNull TextRenderer textRenderer, @NotNull DrawContext context) {
        if (currentMoodHudContext != null) {
            renderMoodStyle(currentMoodHudContext, DEFAULT_FAKE_STYLE);
            return;
        }

        context.getMatrices().push();
        context.getMatrices().translate(currentShakeX, currentShakeY, 0);
        context.getMatrices().translate(0, 3 * moodOffset, 0);
        context.drawGuiTexture(MOOD_KILLER, 5, 6, 14, 17);
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(currentShakeX, currentShakeY, 0);
        context.getMatrices().translate(0, 10 * moodOffset, 0);
        context.getMatrices().translate(26, 8 + textRenderer.fontHeight, 0);
        context.getMatrices().scale((moodTextWidth - 8) * moodRender, 1, 1);
        context.fill(0, 0, 1, 1, MathHelper.hsvToRgb(0F, 1.0F, 0.6F) | ((int) (moodAlpha * 255) << 24));
        context.getMatrices().pop();
    }

    private static void renderMoodStyle(@NotNull MoodHudContext moodContext, @NotNull MoodHudStyle style) {
        DrawContext context = moodContext.drawContext();
        TextRenderer textRenderer = moodContext.textRenderer();

        context.getMatrices().push();
        context.getMatrices().translate(moodContext.shakeX(), moodContext.shakeY(), 0);
        context.getMatrices().translate(0, 3 * moodContext.moodOffset(), 0);

        /*
         * REAL 心情的“跌破阈值向下箭头”仍由 Wathe 判断。
         * 扩展职业只负责提供箭头 sprite，因此 Rememberer/Starstruck 这类自定义箭头不需要复制逻辑。
         */
        if (style.renderArrows() && arrowProgress < 0.1f) {
            if (moodContext.previousMood() >= GameConstants.DEPRESSIVE_MOOD_THRESHOLD && moodContext.moodRender() < GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
                arrowProgress = -1f;
            } else if (moodContext.previousMood() >= GameConstants.MID_MOOD_THRESHOLD && moodContext.moodRender() < GameConstants.MID_MOOD_THRESHOLD) {
                arrowProgress = -1f;
            }
        }

        MoodHudStyle.IconRenderer iconRenderer = style.iconRenderer();
        if (iconRenderer != null) {
            /*
             * 少数职业需要“一次性、按事件起点播放”的图标动画。
             * 这种动画不能交给 GUI sprite atlas 的全局循环计时器，所以开放自定义图标绘制器。
             */
            iconRenderer.render(moodContext);
        } else {
            Identifier mood = style.sprite(moodContext);
            /*
             * 这里使用 GUI sprite id，而不是直接纹理路径。
             * 因此扩展模组只要提供 textures/gui/sprites/...png 和可选 .png.mcmeta，
             * 动态帧动画就会由 Minecraft 的 sprite atlas 自动播放。
             */
            if (mood != null) {
                context.drawGuiTexture(mood, 5, 6, 14, 17);
            }
        }

        if (style.renderArrows()) {
            renderMoodArrow(context, moodContext, style);
        }

        List<Identifier> overlays = style.overlays(moodContext);
        for (Identifier overlay : overlays) {
            if (overlay != null) {
                context.drawGuiTexture(overlay, 5, 6, 14, 17);
            }
        }
        context.getMatrices().pop();

        if (style.shouldRenderBar(moodContext)) {
            MoodHudStyle.BarRenderer barRenderer = style.barRenderer();
            if (barRenderer != null) {
                context.getMatrices().push();
                context.getMatrices().translate(moodContext.shakeX(), moodContext.shakeY(), 0);
                context.getMatrices().translate(0, 10 * moodContext.moodOffset(), 0);
                context.getMatrices().translate(26, 8 + textRenderer.fontHeight, 0);
                barRenderer.render(moodContext, moodContext.moodBarWidth(), moodContext.moodAlpha());
                context.getMatrices().pop();
            }
        }

        if (moodContext.warningProgress() > 0f && style.renderWarningText()) {
            renderWarningText(textRenderer, context, moodContext);
        }
    }

    private static void renderMoodArrow(@NotNull DrawContext context, @NotNull MoodHudContext moodContext, @NotNull MoodHudStyle style) {
        if (Math.abs(arrowProgress) <= 0.01f) {
            return;
        }

        boolean up = arrowProgress > 0;
        Identifier arrow = up ? style.arrowUp(moodContext) : style.arrowDown(moodContext);
        if (arrow == null) {
            return;
        }

        context.getMatrices().push();
        if (!up) {
            context.getMatrices().translate(0, 4, 0);
        }
        context.getMatrices().translate(0, arrowProgress * 4, 0);
        context.drawSprite(7, 6, 0, 10, 13, context.guiAtlasManager.getSprite(arrow), 1f, 1f, 1f, (float) Math.sin(Math.abs(arrowProgress) * Math.PI));
        context.getMatrices().pop();
    }

    private static void renderWarningText(@NotNull TextRenderer textRenderer, @NotNull DrawContext context, @NotNull MoodHudContext moodContext) {
        int pulseColour = getWarningColour(moodContext.warningProgress());
        int warningTextY = 12 + textRenderer.fontHeight;
        context.getMatrices().push();
        context.getMatrices().translate(moodContext.shakeX(), moodContext.shakeY(), 0);
        context.getMatrices().translate(0, 10 * moodContext.moodOffset(), 0);
        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("hud.mood.breakdown_warning"),
                22,
                warningTextY,
                pulseColour | ((int) (moodContext.moodAlpha() * 255) << 24)
        );
        context.getMatrices().pop();
    }

    private static void renderPsycho(@NotNull PlayerEntity player, @NotNull TextRenderer renderer, @NotNull DrawContext context, PlayerPsychoComponent component, @NotNull RenderTickCounter tickCounter, @NotNull MoodHudContext moodContext) {
        int colour = MathHelper.hsvToRgb(0F, 1.0F, 0.5F);
        MutableText text = Text.translatable("game.psycho_mode.text").withColor(colour);
        int width = renderer.getWidth(text);
        random.setSeed(System.currentTimeMillis());
        PsychoMoodHudStyle psychoStyle = MoodHudApi.resolvePsychoStyle(moodContext, component);

        context.getMatrices().push();
        context.getMatrices().translate(random.nextGaussian() / 3, random.nextGaussian() / 3, 0);
        context.enableScissor(22, 6, 180, 23);
        for (int i = -1; i <= 3; i++) {
            float value = 1 - ((player.age + tickCounter.getTickDelta(true)) / 64) % 1;
            context.getMatrices().push();
            context.getMatrices().translate(value * (width + 4), 6, 0);
            context.drawTextWithShadow(renderer, text, i * (width + 4), 0, colour | 255 << 24);
            context.getMatrices().pop();
        }
        context.disableScissor();
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(random.nextGaussian() / 3, random.nextGaussian() / 3, 0);
        context.getMatrices().push();
        context.getMatrices().translate(26, 8 + renderer.fontHeight, 0);
        float duration = Math.max(1f, component.getPsychoTicks() - tickCounter.getTickDelta(true)) / GameConstants.PSYCHO_TIMER;
        context.getMatrices().scale(150 * duration, 1, 1);
        context.fill(0, 0, 1, 1, colour | ((int) (0.9f * 255) << 24));
        context.getMatrices().pop();
        context.getMatrices().pop();

        context.getMatrices().push();
        context.getMatrices().translate(random.nextGaussian() / 3, random.nextGaussian() / 3, 0);
        for (int i = 1; i <= 12; i++) {
            int tick = (player.age - i) * 40;
            if ((player.age - i) % 2 != 0) {
                continue;
            }

            random.setSeed(tick);
            float alpha = (12 - i) / 12f;
            context.getMatrices().push();
            float moodScale = 0.2f + (GameConstants.PSYCHO_MODE_ARMOUR - component.armour) * 0.8f;
            float eyeScale = 0.8f;
            context.getMatrices().translate(
                    (random.nextFloat() - random.nextFloat()) * moodScale * i,
                    (random.nextFloat() - random.nextFloat()) * moodScale * i,
                    -i * 3
            );
            Identifier bodySprite = component.armour == GameConstants.PSYCHO_MODE_ARMOUR ? psychoStyle.body(moodContext, component) : psychoStyle.hitBody(moodContext, component);
            if (bodySprite != null) {
                context.drawSprite(5, 6, 0, 14, 17, context.guiAtlasManager.getSprite(bodySprite), 1f, 1f, 1f, alpha);
            }
            context.getMatrices().translate(
                    (random.nextFloat() - random.nextFloat()) * eyeScale * i,
                    (random.nextFloat() - random.nextFloat()) * eyeScale * i,
                    1
            );
            Identifier eyesSprite = psychoStyle.eyes(moodContext, component);
            if (eyesSprite != null) {
                context.drawSprite(5, 6, 0, 14, 17, context.guiAtlasManager.getSprite(eyesSprite), 1f, 1f, 1f, alpha);
            }
            context.getMatrices().pop();
        }
        context.getMatrices().pop();
    }

    private static class TaskRenderer {
        public int index = 0;
        public float offset = -1f;
        public float alpha = 0.075f;
        public boolean present = false;
        public Text text = Text.empty();

        public boolean tick(@Nullable PlayerMoodComponent.TrainTask present, float delta, boolean isFakeMood) {
            if (present != null) {
                this.text = Text.translatable("task." + (isFakeMood ? "fake" : "feel")).append(Text.translatable("task." + present.getName()));
            }
            this.present = present != null;
            this.alpha = MathHelper.lerp(delta / 16, this.alpha, present != null ? 1f : 0f);
            this.offset = MathHelper.lerp(delta / 32, this.offset, this.index);
            return this.alpha < 0.075f || (((int) (this.alpha * 255.0f) << 24) & -67108864) == 0;
        }
    }
}
