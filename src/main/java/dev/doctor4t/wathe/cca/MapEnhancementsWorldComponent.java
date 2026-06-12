package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.AmbienceConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.CameraShakeConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.FogConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.GravityConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.InteractionBlacklistConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.JumpConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.MovementConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.SceneryConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.SnowParticlesConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.SpecialRolesConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.VisibilityConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.VisualConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfigurationManager;
import dev.doctor4t.wathe.config.datapack.RoomConfig;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 当前维度的地图增强配置组件。
 *
 * <p>服务端读取数据包，客户端只读 CCA 同步缓存。这样渲染 mixin 不需要也不能直接访问
 * 服务端 ResourceManager。</p>
 */
public class MapEnhancementsWorldComponent implements AutoSyncedComponent {
    public static final ComponentKey<MapEnhancementsWorldComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("map_enhancements"), MapEnhancementsWorldComponent.class);

    private final World world;

    private SceneryConfig syncedScenery;
    private VisibilityConfig syncedVisibility;
    private FogConfig syncedFog;
    private CameraShakeConfig syncedCameraShake;
    private SnowParticlesConfig syncedSnowParticles;
    private InteractionBlacklistConfig syncedInteractionBlacklist;
    private GravityConfig syncedGravity;
    private MovementConfig syncedMovement;
    private JumpConfig syncedJump;
    private VisualConfig syncedVisual;
    private AmbienceConfig syncedAmbience;

    public MapEnhancementsWorldComponent(World world) {
        this.world = world;
    }

    public void sync() {
        KEY.sync(this.world);
    }

    private MapEnhancementsConfiguration getConfigForCurrentWorld() {
        Identifier dimId = world.getRegistryKey().getValue();
        MapEnhancementsConfiguration config = MapEnhancementsConfigurationManager.getInstance().getConfiguration(dimId);
        if (config != null) {
            return config;
        }
        return MapEnhancementsConfigurationManager.getInstance().getConfiguration();
    }

    public boolean hasAreaConfiguration() {
        return getConfigForCurrentWorld() != null;
    }

    public SceneryConfig getSceneryConfig() {
        if (world.isClient() && syncedScenery != null) return syncedScenery;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getSceneryOrDefault() : SceneryConfig.DEFAULT;
    }

    public VisibilityConfig getVisibilityConfig() {
        if (world.isClient() && syncedVisibility != null) return syncedVisibility;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getVisibilityOrDefault() : VisibilityConfig.DEFAULT;
    }

    public FogConfig getFogConfig() {
        if (world.isClient() && syncedFog != null) return syncedFog;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getFogOrDefault() : FogConfig.DEFAULT;
    }

    public CameraShakeConfig getCameraShakeConfig() {
        if (world.isClient() && syncedCameraShake != null) return syncedCameraShake;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getCameraShakeOrDefault() : CameraShakeConfig.DEFAULT;
    }

    public SnowParticlesConfig getSnowParticlesConfig() {
        if (world.isClient() && syncedSnowParticles != null) return syncedSnowParticles;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getSnowParticlesOrDefault() : SnowParticlesConfig.DEFAULT;
    }

    public InteractionBlacklistConfig getInteractionBlacklistConfig() {
        if (world.isClient() && syncedInteractionBlacklist != null) return syncedInteractionBlacklist;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getInteractionBlacklistOrDefault() : InteractionBlacklistConfig.DEFAULT;
    }

    public GravityConfig getGravityConfig() {
        if (world.isClient() && syncedGravity != null) return syncedGravity;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getGravityOrDefault() : GravityConfig.DEFAULT;
    }

    public MovementConfig getMovementConfig() {
        if (world.isClient() && syncedMovement != null) return syncedMovement;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getMovementOrDefault() : MovementConfig.DEFAULT;
    }

    public JumpConfig getJumpConfig() {
        if (world.isClient() && syncedJump != null) return syncedJump;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getJumpOrDefault() : JumpConfig.DEFAULT;
    }

    public VisualConfig getVisualConfig() {
        if (world.isClient() && syncedVisual != null) return syncedVisual;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getVisualOrDefault() : VisualConfig.DEFAULT;
    }

    public boolean hasVisualConfig() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null && config.visual().isPresent();
    }

    public boolean hasJumpConfig() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null && config.jump().isPresent();
    }

    public boolean hasFogConfig() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null && config.fog().isPresent();
    }

    public boolean hasSnowParticlesConfig() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null && config.snowParticles().isPresent();
    }

    public AmbienceConfig getAmbienceConfig() {
        if (world.isClient() && syncedAmbience != null) return syncedAmbience;
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getAmbienceOrDefault() : AmbienceConfig.DEFAULT;
    }

    public List<String> getEnabledSpecialRoles() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getSpecialRolesOrDefault().enabledRoles() : List.of();
    }

    public int getRoomCount() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getRoomCount() : 0;
    }

    public Optional<RoomConfig> getRoomConfig(int roomNumber) {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getRoomConfig(roomNumber) : Optional.empty();
    }

    public Optional<RoomConfig.SpawnPoint> getSpawnPointForPlayer(int roomNumber, int playerIndexInRoom) {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getSpawnPointForPlayer(roomNumber, playerIndexInRoom) : Optional.empty();
    }

    public int getTotalRoomCapacity() {
        MapEnhancementsConfiguration config = getConfigForCurrentWorld();
        return config != null ? config.getTotalCapacity() : 0;
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        if (tag.contains("sceneryHeightOffset")) {
            this.syncedScenery = new SceneryConfig(
                    tag.getInt("sceneryHeightOffset"),
                    tag.getInt("sceneryMinX"),
                    tag.getInt("sceneryMaxX"),
                    tag.getInt("sceneryMinZ"),
                    tag.getInt("sceneryMaxZ")
            );
        }
        if (tag.contains("visibilityDay")) {
            this.syncedVisibility = new VisibilityConfig(
                    tag.getInt("visibilityDay"),
                    tag.getInt("visibilityNight"),
                    tag.getInt("visibilitySundown")
            );
        }
        if (tag.contains("fogStart")) {
            this.syncedFog = new FogConfig(
                    !tag.contains("fogEnabled") || tag.getBoolean("fogEnabled"),
                    tag.getFloat("fogStart"),
                    tag.getFloat("fogEndMoving"),
                    tag.getFloat("fogEndStationary"),
                    tag.getInt("fogNightColor")
            );
        }
        if (tag.contains("cameraShakeEnabled")) {
            this.syncedCameraShake = new CameraShakeConfig(
                    tag.getBoolean("cameraShakeEnabled"),
                    tag.getFloat("cameraShakeAmplitudeIndoor"),
                    tag.getFloat("cameraShakeAmplitudeOutdoor"),
                    tag.getFloat("cameraShakeStrengthIndoor"),
                    tag.getFloat("cameraShakeStrengthOutdoor")
            );
        }
        if (tag.contains("snowParticlesEnabled")) {
            this.syncedSnowParticles = new SnowParticlesConfig(
                    tag.getBoolean("snowParticlesEnabled"),
                    tag.getInt("snowParticlesCount"),
                    tag.getFloat("snowParticlesSpawnOffsetX"),
                    tag.getFloat("snowParticlesSpawnRangeY"),
                    tag.getFloat("snowParticlesSpawnRangeZ")
            );
        }
        if (tag.contains("blacklistBlocksCount")) {
            int blocksCount = tag.getInt("blacklistBlocksCount");
            List<String> blocks = new ArrayList<>();
            for (int i = 0; i < blocksCount; i++) {
                blocks.add(tag.getString("blacklistBlock_" + i));
            }

            int tagsCount = tag.getInt("blacklistTagsCount");
            List<String> blockTags = new ArrayList<>();
            for (int i = 0; i < tagsCount; i++) {
                blockTags.add(tag.getString("blacklistTag_" + i));
            }
            this.syncedInteractionBlacklist = new InteractionBlacklistConfig(blocks, blockTags);
        }
        if (tag.contains("gravityMultiplier")) {
            this.syncedGravity = new GravityConfig(tag.getFloat("gravityMultiplier"));
        }
        if (tag.contains("movementWalkMultiplier")) {
            this.syncedMovement = new MovementConfig(
                    tag.getFloat("movementWalkMultiplier"),
                    tag.getFloat("movementSprintMultiplier")
            );
        }
        if (tag.contains("jumpAllowed")) {
            this.syncedJump = new JumpConfig(
                    tag.getBoolean("jumpAllowed"),
                    tag.getFloat("jumpStaminaCost")
            );
        }
        if (tag.contains("visualStaticMap")) {
            this.syncedVisual = new VisualConfig(
                    tag.getBoolean("visualStaticMap"),
                    tag.getBoolean("visualHud"),
                    tag.getInt("visualTrainSpeed"),
                    TrainWorldComponent.TimeOfDay.valueOf(tag.getString("visualTimeOfDay"))
            );
        }
        if (tag.contains("ambienceInsideSound")) {
            boolean requireTrainMoving = !tag.contains("ambienceRequireTrainMoving") || tag.getBoolean("ambienceRequireTrainMoving");
            String inside = tag.getString("ambienceInsideSound");
            String outside = tag.getString("ambienceOutsideSound");
            this.syncedAmbience = new AmbienceConfig(
                    requireTrainMoving,
                    inside.isEmpty() ? Optional.empty() : Optional.of(inside),
                    outside.isEmpty() ? Optional.empty() : Optional.of(outside)
            );
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        SceneryConfig scenery = getSceneryConfig();
        tag.putInt("sceneryHeightOffset", scenery.heightOffset());
        tag.putInt("sceneryMinX", scenery.minX());
        tag.putInt("sceneryMaxX", scenery.maxX());
        tag.putInt("sceneryMinZ", scenery.minZ());
        tag.putInt("sceneryMaxZ", scenery.maxZ());

        VisibilityConfig visibility = getVisibilityConfig();
        tag.putInt("visibilityDay", visibility.day());
        tag.putInt("visibilityNight", visibility.night());
        tag.putInt("visibilitySundown", visibility.sundown());

        FogConfig fog = getFogConfig();
        tag.putBoolean("fogEnabled", fog.enabled());
        tag.putFloat("fogStart", fog.start());
        tag.putFloat("fogEndMoving", fog.endMoving());
        tag.putFloat("fogEndStationary", fog.endStationary());
        tag.putInt("fogNightColor", fog.nightColor());

        CameraShakeConfig cameraShake = getCameraShakeConfig();
        tag.putBoolean("cameraShakeEnabled", cameraShake.enabled());
        tag.putFloat("cameraShakeAmplitudeIndoor", cameraShake.amplitudeIndoor());
        tag.putFloat("cameraShakeAmplitudeOutdoor", cameraShake.amplitudeOutdoor());
        tag.putFloat("cameraShakeStrengthIndoor", cameraShake.strengthIndoor());
        tag.putFloat("cameraShakeStrengthOutdoor", cameraShake.strengthOutdoor());

        SnowParticlesConfig snowParticles = getSnowParticlesConfig();
        tag.putBoolean("snowParticlesEnabled", snowParticles.enabled());
        tag.putInt("snowParticlesCount", snowParticles.count());
        tag.putFloat("snowParticlesSpawnOffsetX", snowParticles.spawnOffsetX());
        tag.putFloat("snowParticlesSpawnRangeY", snowParticles.spawnRangeY());
        tag.putFloat("snowParticlesSpawnRangeZ", snowParticles.spawnRangeZ());

        InteractionBlacklistConfig blacklist = getInteractionBlacklistConfig();
        tag.putInt("blacklistBlocksCount", blacklist.blocks().size());
        for (int i = 0; i < blacklist.blocks().size(); i++) {
            tag.putString("blacklistBlock_" + i, blacklist.blocks().get(i));
        }
        tag.putInt("blacklistTagsCount", blacklist.blockTags().size());
        for (int i = 0; i < blacklist.blockTags().size(); i++) {
            tag.putString("blacklistTag_" + i, blacklist.blockTags().get(i));
        }

        GravityConfig gravity = getGravityConfig();
        tag.putFloat("gravityMultiplier", gravity.gravityMultiplier());

        MovementConfig movement = getMovementConfig();
        tag.putFloat("movementWalkMultiplier", movement.walkSpeedMultiplier());
        tag.putFloat("movementSprintMultiplier", movement.sprintSpeedMultiplier());

        JumpConfig jump = getJumpConfig();
        tag.putBoolean("jumpAllowed", jump.allowed());
        tag.putFloat("jumpStaminaCost", jump.staminaCost());

        VisualConfig visual = getVisualConfig();
        tag.putBoolean("visualStaticMap", visual.staticMap());
        tag.putBoolean("visualHud", visual.hud());
        tag.putInt("visualTrainSpeed", visual.trainSpeed());
        tag.putString("visualTimeOfDay", visual.timeOfDay().name());

        AmbienceConfig ambience = getAmbienceConfig();
        tag.putBoolean("ambienceRequireTrainMoving", ambience.requireTrainMoving());
        tag.putString("ambienceInsideSound", ambience.insideSound().orElse(""));
        tag.putString("ambienceOutsideSound", ambience.outsideSound().orElse(""));
    }
}
