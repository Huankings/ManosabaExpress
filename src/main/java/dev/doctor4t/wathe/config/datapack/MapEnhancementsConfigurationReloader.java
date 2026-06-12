package dev.doctor4t.wathe.config.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.doctor4t.wathe.Wathe;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 从数据包读取地图配置。
 *
 * <p>正式路径是 {@code data/wathe/maps/*.json}。如果没有任何新格式地图，
 * 仍兼容旧的 {@code data/wathe/areas/*.json}，并注册成 overworld 地图。</p>
 */
public class MapEnhancementsConfigurationReloader implements SimpleSynchronousResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final String LEGACY_DATA_PATH = "areas";
    private static final String MAPS_DATA_PATH = "maps";

    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new MapEnhancementsConfigurationReloader());
        Wathe.LOGGER.info("Registered Wathe map configuration reloader");
    }

    @Override
    public Identifier getFabricId() {
        return Wathe.id("area_configuration");
    }

    @Override
    public void reload(ResourceManager manager) {
        Wathe.LOGGER.info("Reloading Wathe map configurations...");
        MapRegistry.getInstance().clear();

        Map<Identifier, Resource> mapResources = manager.findResources(MAPS_DATA_PATH, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : mapResources.entrySet()) {
            Identifier resourceId = entry.getKey();
            if (!resourceId.getNamespace().equals(Wathe.MOD_ID)) {
                continue;
            }

            try (InputStreamReader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                Optional<MapRegistryEntry> result = MapRegistryEntry.CODEC
                        .parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> Wathe.LOGGER.error("Failed to parse map config {}: {}", resourceId, error));

                if (result.isPresent()) {
                    String path = resourceId.getPath();
                    String name = path.substring(MAPS_DATA_PATH.length() + 1, path.length() - 5);
                    Identifier mapId = Identifier.of(resourceId.getNamespace(), name);
                    MapRegistry.getInstance().register(mapId, result.get());
                    Wathe.LOGGER.info("Registered Wathe map '{}' (dimension: {}) from {}", mapId, result.get().dimensionId(), resourceId);
                }
            } catch (Exception e) {
                Wathe.LOGGER.error("Error loading Wathe map config from {}", resourceId, e);
            }
        }

        if (MapRegistry.getInstance().getMapCount() == 0) {
            loadLegacyAreaConfig(manager);
        }

        Wathe.LOGGER.info("Wathe map registry loaded: {} maps registered", MapRegistry.getInstance().getMapCount());
    }

    private void loadLegacyAreaConfig(ResourceManager manager) {
        Map<Identifier, Resource> legacyResources = manager.findResources(LEGACY_DATA_PATH, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> entry : legacyResources.entrySet()) {
            Identifier resourceId = entry.getKey();
            if (!resourceId.getNamespace().equals(Wathe.MOD_ID)) {
                continue;
            }

            try (InputStreamReader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                Optional<MapEnhancementsConfiguration> result = MapEnhancementsConfiguration.CODEC
                        .parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> Wathe.LOGGER.error("Failed to parse legacy area config {}: {}", resourceId, error));

                if (result.isPresent()) {
                    MapRegistryEntry legacyEntry = new MapRegistryEntry(
                            Identifier.ofVanilla("overworld"),
                            "Overworld",
                            Optional.empty(),
                            result.get(),
                            0,
                            100
                    );
                    MapRegistry.getInstance().register(Wathe.id("legacy_overworld"), legacyEntry);
                    Wathe.LOGGER.info("Loaded legacy Wathe area config from {} as overworld map", resourceId);
                    break;
                }
            } catch (Exception e) {
                Wathe.LOGGER.error("Error loading legacy Wathe area config from {}", resourceId, e);
            }
        }
    }
}
