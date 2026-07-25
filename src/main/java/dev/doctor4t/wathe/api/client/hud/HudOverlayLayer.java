package dev.doctor4t.wathe.api.client.hud;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Wathe 通用 HUD 叠加层的渲染阶段。
 *
 * <p>不同扩展 HUD 对“画在谁前面/后面”的要求不同：
 * 右下角状态文字通常只需要跟随普通 HUD；控制、绑架这类黑屏提示需要尽早盖住画面；
 * 狙击镜遮罩则必须在所有 HUD 之后再绘制。把阶段拆开后，扩展职业就不用继续混入
 * {@code InGameHud#render}/{@code renderMainHud} 去抢具体注入点。</p>
 */
@Environment(EnvType.CLIENT)
public enum HudOverlayLayer {
    BEFORE_HUD,
    MAIN_HUD,
    AFTER_HUD
}
