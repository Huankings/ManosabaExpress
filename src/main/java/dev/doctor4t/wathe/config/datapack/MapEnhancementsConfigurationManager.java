package dev.doctor4t.wathe.config.datapack;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * 地图增强配置查询入口。
 */
public final class MapEnhancementsConfigurationManager {
    private static final MapEnhancementsConfigurationManager INSTANCE = new MapEnhancementsConfigurationManager();

    private MapEnhancementsConfigurationManager() {
    }

    public static MapEnhancementsConfigurationManager getInstance() {
        return INSTANCE;
    }

    @Nullable
    public MapEnhancementsConfiguration getConfiguration(Identifier dimensionId) {
        MapRegistryEntry entry = MapRegistry.getInstance().getMap(dimensionId);
        if (entry != null) {
            return entry.enhancements();
        }
        for (MapRegistryEntry mapEntry : MapRegistry.getInstance().getMaps().values()) {
            if (mapEntry.dimensionId().equals(dimensionId)) {
                return mapEntry.enhancements();
            }
        }
        return null;
    }

    @Nullable
    public MapEnhancementsConfiguration getConfiguration() {
        return getConfiguration(Identifier.ofVanilla("overworld"));
    }

    public boolean hasConfiguration() {
        return MapRegistry.getInstance().getMapCount() > 0;
    }

    public boolean hasConfiguration(Identifier dimensionId) {
        return getConfiguration(dimensionId) != null;
    }
}
