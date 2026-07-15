package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.util.ShopEntry;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通用商店修改器。
 *
 * <p>如果扩展模组只是想“在杀手通用商店里追加一个商品”或“按职业调一下价格”，
 * 不需要注册完整的职业专属商店；注册一个修改器并在这里改动传入的列表即可。</p>
 */
@FunctionalInterface
public interface ShopModifier {
    void modify(@NotNull ShopContext context, @NotNull List<ShopEntry> entries);
}
