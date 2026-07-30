package dev.doctor4t.wathe.api.psycho;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * 疯魔状态的客户端视觉配置。
 *
 * <p>这个类只保存资源 id 和布尔开关，因此可以安全放在 main 源集里同步给服务端编译。
 * 真正创建 {@code SkinTextures} 的工作在客户端 API 中完成，避免服务端加载客户端类。</p>
 */
public record PsychoVisualSettings(
        @Nullable Identifier wideSkinTexture,
        @Nullable Identifier slimSkinTexture,
        boolean hideFeatures
) {
    public static PsychoVisualSettings none() {
        return new PsychoVisualSettings(null, null, false);
    }

    public static PsychoVisualSettings skin(@Nullable Identifier wideSkinTexture, @Nullable Identifier slimSkinTexture, boolean hideFeatures) {
        return new PsychoVisualSettings(wideSkinTexture, slimSkinTexture, hideFeatures);
    }

    public @Nullable Identifier texture(boolean slimModel) {
        if (slimModel && this.slimSkinTexture != null) {
            return this.slimSkinTexture;
        }
        return this.wideSkinTexture;
    }
}
