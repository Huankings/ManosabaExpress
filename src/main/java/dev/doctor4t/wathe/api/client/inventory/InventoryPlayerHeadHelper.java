package dev.doctor4t.wathe.api.client.inventory;

import dev.doctor4t.wathe.api.client.appearance.PlayerAppearanceApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 背包头像的稳定皮肤解析工具。
 *
 * <p>这里故意只走玩家列表 / Wathe 缓存里的原始皮肤，不读取世界中玩家实体当前
 * {@code getSkinTextures()}。这样 Morphling、Controller、Convener 等伪装效果不会把
 * 临时皮肤泄露到选人头像 UI 里。</p>
 */
@Environment(EnvType.CLIENT)
public final class InventoryPlayerHeadHelper {
    private InventoryPlayerHeadHelper() {
    }

    public static @NotNull SkinTextures resolveStableSkinTextures(@NotNull UUID targetUuid, @Nullable PlayerListEntry preferredEntry) {
        if (preferredEntry != null) {
            return preferredEntry.getSkinTextures();
        }
        return PlayerAppearanceApi.resolveOriginalSkinTextures(targetUuid, true);
    }
}
