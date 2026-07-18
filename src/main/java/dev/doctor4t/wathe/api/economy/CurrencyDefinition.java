package dev.doctor4t.wathe.api.economy;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 货币 / 材料在 Wathe 经济系统里的显示定义。
 *
 * <p>这里只保存“怎么显示”和“什么时候允许显示 HUD”。真正的数量存放在
 * {@code PlayerShopComponent} 里，避免每个扩展模组各自维护一套同步字段。</p>
 */
public record CurrencyDefinition(
        @NotNull Identifier id,
        @NotNull String icon,
        @NotNull String translationKey,
        long order,
        @NotNull EconomyApi.CurrencyHudPredicate hudPredicate
) {
    public CurrencyDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(translationKey, "translationKey");
        Objects.requireNonNull(hudPredicate, "hudPredicate");
    }
}
