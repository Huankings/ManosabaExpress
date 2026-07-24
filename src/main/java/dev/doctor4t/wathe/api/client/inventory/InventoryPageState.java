package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 背包分页的客户端页码缓存。
 *
 * <p>缓存按 Identifier 隔离：变形怪翻到第 2 页不会影响交换者或其它 mod 的界面。
 * WatheClient 会在断线/换局时清空，避免上一局页码污染下一局。</p>
 */
@Environment(EnvType.CLIENT)
public final class InventoryPageState {
    private static final Map<Identifier, Integer> PAGE_CACHE = new HashMap<>();

    private InventoryPageState() {
    }

    public static int getPage(@NotNull Identifier key) {
        return PAGE_CACHE.getOrDefault(key, 0);
    }

    public static void setPage(@NotNull Identifier key, int page) {
        PAGE_CACHE.put(key, Math.max(0, page));
    }

    public static void reset() {
        PAGE_CACHE.clear();
    }
}
