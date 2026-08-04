package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.hud.HudOverlayApi;
import dev.doctor4t.wathe.api.client.hud.HudOverlayLayer;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.WorldBlackoutComponent;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.MathHelper;

/**
 * Wathe 本体停电黑幕 HUD。
 *
 * <p>这份逻辑从 kinssaba 的 BetterBlackout 迁移而来，但不再监听客户端音效来猜停电时间。
 * 黑幕只读取服务端同步的 {@link WorldBlackoutComponent}，并且要求对局处于 ACTIVE、
 * 玩家仍按 Wathe 玩法存活、当前世界真实停电中。这样停局和下一局开始时不会继承旧客户端计时。</p>
 */
public final class BlackoutOverlayRenderer {
    private static final long FADE_MILLIS = 500L;

    private static long insideTime = 0L;
    private static boolean outside = true;
    private static long outsideTime = 0L;
    private static boolean wasInside = false;
    private static long instinctChangeTime = 0L;
    private static boolean lastInstinctState = false;
    private static float instinctStartAlpha = 0f;
    private static float currentAlpha = 0f;

    private BlackoutOverlayRenderer() {
    }

    public static void register() {
        HudOverlayApi.register(Wathe.id("hud/blackout_overlay"), HudOverlayLayer.BEFORE_HUD, HudOverlayApi.DEFAULT_PRIORITY, context -> {
            WorldBlackoutComponent blackout = WorldBlackoutComponent.KEY.get(context.player().getWorld());
            if (context.gameWorld().getGameStatus() != GameWorldComponent.GameStatus.ACTIVE
                    || !context.aliveAndSurvival()
                    || !blackout.isBlackoutActive()
                    || blackout.getOverlayOpacityPercent() <= 0
                    || context.player().hasStatusEffect(StatusEffects.NIGHT_VISION)) {
                resetTransitionState();
                return;
            }

            long currentTime = System.currentTimeMillis();
            boolean isOutside = Wathe.isSkyVisibleAdjacent(context.player());
            boolean isInstinctEnabled = WatheClient.isInstinctEnabled();
            if (isInstinctEnabled != lastInstinctState) {
                instinctChangeTime = currentTime;
                instinctStartAlpha = currentAlpha;
                lastInstinctState = isInstinctEnabled;
            }
            if (outside && !isOutside) {
                insideTime = currentTime;
            }
            if (!outside && isOutside) {
                outsideTime = currentTime;
                wasInside = true;
            }
            outside = isOutside;

            int targetAlpha = getBlackoutAlpha(blackout, context.tickDelta());
            int alpha = calculateAlpha(isOutside, isInstinctEnabled, targetAlpha, currentTime);
            currentAlpha = alpha;
            if (alpha > 0) {
                context.drawContext().fill(0, 0, context.width(), context.height(), alpha << 24);
            }
        });
    }

    private static void resetTransitionState() {
        insideTime = 0L;
        outside = true;
        outsideTime = 0L;
        wasInside = false;
        instinctChangeTime = 0L;
        lastInstinctState = false;
        instinctStartAlpha = 0f;
        currentAlpha = 0f;
    }

    private static int calculateAlpha(boolean isOutside, boolean isInstinctEnabled, int targetAlpha, long currentTime) {
        long timeSinceInstinctChange = currentTime - instinctChangeTime;
        float instinctProgress = MathHelper.clamp((float) timeSinceInstinctChange / FADE_MILLIS, 0f, 1f);
        float baseTarget;
        if (isOutside) {
            if (wasInside) {
                long timeOutside = currentTime - outsideTime;
                if (timeOutside < FADE_MILLIS) {
                    float outsideProgress = (float) timeOutside / FADE_MILLIS;
                    baseTarget = targetAlpha * (1f - outsideProgress);
                } else {
                    wasInside = false;
                    baseTarget = 0f;
                }
            } else {
                baseTarget = 0f;
            }
        } else {
            long timeInside = currentTime - insideTime;
            if (timeInside < FADE_MILLIS) {
                float insideProgress = (float) timeInside / FADE_MILLIS;
                baseTarget = targetAlpha * insideProgress;
            } else {
                baseTarget = targetAlpha;
            }
        }

        float finalTarget = isInstinctEnabled ? 0f : baseTarget;
        if (timeSinceInstinctChange < FADE_MILLIS) {
            return (int) (instinctStartAlpha + (finalTarget - instinctStartAlpha) * instinctProgress);
        }
        return (int) finalTarget;
    }

    private static int getBlackoutAlpha(WorldBlackoutComponent blackout, float tickDelta) {
        int minTicks = blackout.getMinDurationTicks();
        int maxTicks = blackout.getMaxDurationTicks();
        float elapsedTicks = Math.max(0f, blackout.getElapsedTicks() + tickDelta);
        float opacity = blackout.getOverlayOpacityPercent() / 100f;
        int fullAlpha = (int) (255f * opacity);
        if (elapsedTicks < minTicks) {
            return fullAlpha;
        }

        float fadeDuration = Math.max(1f, maxTicks - minTicks);
        float progress = MathHelper.clamp((elapsedTicks - minTicks) / fadeDuration, 0f, 1f);
        return (int) (fullAlpha * (1f - progress));
    }
}
