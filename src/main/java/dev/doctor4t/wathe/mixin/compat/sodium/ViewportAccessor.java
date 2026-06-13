package dev.doctor4t.wathe.mixin.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 Sodium Viewport 内部的 Frustum。
 *
 * <p>Sodium 的公开 API 只提供整数中心点的 isBoxVisible；风景虚拟坐标的 X 可能是小数，
 * 所以这里拿到底层 Frustum 后用虚拟包围盒直接测试。</p>
 */
@Mixin(value = Viewport.class, remap = false)
public interface ViewportAccessor {
    @Accessor(value = "frustum", remap = false)
    Frustum wathe$getFrustum();
}
