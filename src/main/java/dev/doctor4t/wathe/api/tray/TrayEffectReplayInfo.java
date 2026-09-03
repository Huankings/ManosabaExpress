package dev.doctor4t.wathe.api.tray;

import org.jetbrains.annotations.Nullable;

/**
 * 托盘效果在回放中的显示信息。
 *
 * <p>回放不应该再要求扩展模组为“放置 / 取出 / 食用”分别注册 formatter。
 * 效果处理器只需要提供一个稳定的本地化 key 和英文兜底，Wathe 就能套用统一句式。</p>
 */
public record TrayEffectReplayInfo(String translationKey, String fallback) {
    public TrayEffectReplayInfo {
        if (translationKey == null || translationKey.isBlank()) {
            throw new IllegalArgumentException("translationKey must not be blank");
        }
        if (fallback == null) {
            fallback = translationKey;
        }
    }
}
