package dev.doctor4t.wathe.config.datapack;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据包地图注册表。
 *
 * <p>服务器重载数据包时会刷新这里；投票组件只读取这个注册表，
 * 不直接碰 ResourceManager，避免投票逻辑和数据包加载逻辑互相缠住。</p>
 */
public final class MapRegistry {
    private static final MapRegistry INSTANCE = new MapRegistry();

    private final Map<Identifier, MapRegistryEntry> maps = new LinkedHashMap<>();

    private MapRegistry() {
    }

    public static MapRegistry getInstance() {
        return INSTANCE;
    }

    public void register(Identifier id, MapRegistryEntry entry) {
        maps.put(id, entry);
    }

    public void clear() {
        maps.clear();
    }

    public Map<Identifier, MapRegistryEntry> getMaps() {
        return Collections.unmodifiableMap(maps);
    }

    public MapRegistryEntry getMap(Identifier id) {
        return maps.get(id);
    }

    public int getMapCount() {
        return maps.size();
    }

    public int getMinimumRequiredPlayers() {
        int minimum = Integer.MAX_VALUE;
        for (MapRegistryEntry entry : maps.values()) {
            minimum = Math.min(minimum, Math.max(0, entry.minPlayers()));
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    public Set<Identifier> getMapIds() {
        return Collections.unmodifiableSet(maps.keySet());
    }

    public List<MapRegistryEntry> getEligibleMaps(int playerCount) {
        List<MapRegistryEntry> eligible = new ArrayList<>();
        for (MapRegistryEntry entry : maps.values()) {
            if (entry.isEligible(playerCount)) {
                eligible.add(entry);
            }
        }
        return eligible;
    }

    public List<MapRegistryEntry> getAllMaps() {
        return new ArrayList<>(maps.values());
    }
}
