package dev.doctor4t.wathe.config.datapack;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.doctor4t.wathe.cca.TrainWorldComponent;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 地图增强配置。
 *
 * <p>这些字段来自数据包 {@code data/wathe/maps/*.json}，用于补足原 Wathe
 * MapVariables 没覆盖的内容：房间、视觉、移动、重力、交互黑名单等。</p>
 */
public record MapEnhancementsConfiguration(
        List<RoomConfig> rooms,
        Optional<SceneryConfig> scenery,
        Optional<VisibilityConfig> visibility,
        Optional<FogConfig> fog,
        Optional<CameraShakeConfig> cameraShake,
        Optional<SnowParticlesConfig> snowParticles,
        Optional<InteractionBlacklistConfig> interactionBlacklist,
        Optional<GravityConfig> gravity,
        Optional<MovementConfig> movement,
        Optional<JumpConfig> jump,
        Optional<VisualConfig> visual,
        Optional<AmbienceConfig> ambience,
        Optional<SpecialRolesConfig> specialRoles
) {
    public static final MapEnhancementsConfiguration EMPTY = new MapEnhancementsConfiguration(
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
    );

    public record SceneryConfig(int heightOffset, int minX, int maxX, int minZ, int maxZ) {
        public static final SceneryConfig DEFAULT = new SceneryConfig(116, -208, 303, -896, -177);

        public static final Codec<SceneryConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("height_offset", 116).forGetter(SceneryConfig::heightOffset),
                Codec.INT.optionalFieldOf("min_x", -208).forGetter(SceneryConfig::minX),
                Codec.INT.optionalFieldOf("max_x", 303).forGetter(SceneryConfig::maxX),
                Codec.INT.optionalFieldOf("min_z", -896).forGetter(SceneryConfig::minZ),
                Codec.INT.optionalFieldOf("max_z", -177).forGetter(SceneryConfig::maxZ)
        ).apply(instance, SceneryConfig::new));
    }

    public record VisibilityConfig(int day, int night, int sundown) {
        public static final VisibilityConfig DEFAULT = new VisibilityConfig(400, 200, 300);

        public static final Codec<VisibilityConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("day").forGetter(VisibilityConfig::day),
                Codec.INT.fieldOf("night").forGetter(VisibilityConfig::night),
                Codec.INT.fieldOf("sundown").forGetter(VisibilityConfig::sundown)
        ).apply(instance, VisibilityConfig::new));
    }

    public record FogConfig(boolean enabled, float start, float endMoving, float endStationary, int nightColor) {
        public static final FogConfig DEFAULT = new FogConfig(true, 32.0f, 96.0f, 64.0f, 0x0D0D14);

        private static final Codec<Integer> NIGHT_COLOR_CODEC = Codec.either(Codec.INT, Codec.STRING)
                .flatXmap(
                        either -> either.map(DataResult::success, FogConfig::parseNightColor),
                        value -> DataResult.success(Either.left(value))
                );

        public static final Codec<FogConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(FogConfig::enabled),
                Codec.FLOAT.optionalFieldOf("start", 32.0f).forGetter(FogConfig::start),
                Codec.FLOAT.optionalFieldOf("end_moving", 96.0f).forGetter(FogConfig::endMoving),
                Codec.FLOAT.optionalFieldOf("end_stationary", 64.0f).forGetter(FogConfig::endStationary),
                NIGHT_COLOR_CODEC.optionalFieldOf("night_color", 0x0D0D14).forGetter(FogConfig::nightColor)
        ).apply(instance, FogConfig::new));

        private static DataResult<Integer> parseNightColor(String raw) {
            String value = raw.trim();
            if (value.startsWith("#")) {
                value = value.substring(1);
            } else if (value.startsWith("0x") || value.startsWith("0X")) {
                value = value.substring(2);
            }
            if (value.isEmpty()) {
                return DataResult.error(() -> "night_color hex string is empty");
            }
            if (!value.matches("[0-9a-fA-F]+")) {
                return DataResult.error(() -> "night_color must be a hex string like #RRGGBB or 0xAARRGGBB");
            }
            if (value.length() > 8) {
                return DataResult.error(() -> "night_color hex string is too long");
            }
            try {
                return DataResult.success((int) Long.parseLong(value, 16));
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "Invalid night_color hex string: " + raw);
            }
        }
    }

    public record CameraShakeConfig(boolean enabled, float amplitudeIndoor, float amplitudeOutdoor, float strengthIndoor, float strengthOutdoor) {
        public static final CameraShakeConfig DEFAULT = new CameraShakeConfig(true, 0.002f, 0.006f, 0.04f, 0.08f);

        public static final Codec<CameraShakeConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(CameraShakeConfig::enabled),
                Codec.FLOAT.optionalFieldOf("amplitude_indoor", 0.002f).forGetter(CameraShakeConfig::amplitudeIndoor),
                Codec.FLOAT.optionalFieldOf("amplitude_outdoor", 0.006f).forGetter(CameraShakeConfig::amplitudeOutdoor),
                Codec.FLOAT.optionalFieldOf("strength_indoor", 0.04f).forGetter(CameraShakeConfig::strengthIndoor),
                Codec.FLOAT.optionalFieldOf("strength_outdoor", 0.08f).forGetter(CameraShakeConfig::strengthOutdoor)
        ).apply(instance, CameraShakeConfig::new));
    }

    public record SnowParticlesConfig(boolean enabled, int count, float spawnOffsetX, float spawnRangeY, float spawnRangeZ) {
        public static final SnowParticlesConfig DEFAULT = new SnowParticlesConfig(true, 350, -180f, 48f, 32f);

        public static final Codec<SnowParticlesConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(SnowParticlesConfig::enabled),
                Codec.INT.optionalFieldOf("count", 350).forGetter(SnowParticlesConfig::count),
                Codec.FLOAT.optionalFieldOf("spawn_offset_x", -180f).forGetter(SnowParticlesConfig::spawnOffsetX),
                Codec.FLOAT.optionalFieldOf("spawn_range_y", 48f).forGetter(SnowParticlesConfig::spawnRangeY),
                Codec.FLOAT.optionalFieldOf("spawn_range_z", 32f).forGetter(SnowParticlesConfig::spawnRangeZ)
        ).apply(instance, SnowParticlesConfig::new));
    }

    public record GravityConfig(float gravityMultiplier) {
        public static final GravityConfig DEFAULT = new GravityConfig(1.0f);

        public static final Codec<GravityConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("gravity_multiplier", 1.0f).forGetter(GravityConfig::gravityMultiplier)
        ).apply(instance, GravityConfig::new));
    }

    public record MovementConfig(float walkSpeedMultiplier, float sprintSpeedMultiplier) {
        public static final MovementConfig DEFAULT = new MovementConfig(1.0f, 1.0f);

        public static final Codec<MovementConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("walk_speed_multiplier", 1.0f).forGetter(MovementConfig::walkSpeedMultiplier),
                Codec.FLOAT.optionalFieldOf("sprint_speed_multiplier", 1.0f).forGetter(MovementConfig::sprintSpeedMultiplier)
        ).apply(instance, MovementConfig::new));
    }

    public record JumpConfig(boolean allowed, float staminaCost) {
        public static final JumpConfig DEFAULT = new JumpConfig(true, 0f);

        public static final Codec<JumpConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("allowed", true).forGetter(JumpConfig::allowed),
                Codec.FLOAT.optionalFieldOf("stamina_cost", 0f).forGetter(JumpConfig::staminaCost)
        ).apply(instance, JumpConfig::new));
    }

    public record VisualConfig(boolean staticMap, boolean hud, int trainSpeed, TrainWorldComponent.TimeOfDay timeOfDay) {
        public static final VisualConfig DEFAULT = new VisualConfig(false, false, 100, TrainWorldComponent.TimeOfDay.DAY);

        private static final Codec<TrainWorldComponent.TimeOfDay> TIME_OF_DAY_CODEC = Codec.STRING.comapFlatMap(raw -> {
            try {
                return DataResult.success(TrainWorldComponent.TimeOfDay.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Unknown time_of_day '" + raw + "'");
            }
        }, TrainWorldComponent.TimeOfDay::name);

        public static final Codec<VisualConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("static_map", false).forGetter(VisualConfig::staticMap),
                Codec.BOOL.optionalFieldOf("hud", false).forGetter(VisualConfig::hud),
                Codec.INT.optionalFieldOf("train_speed", 100).forGetter(VisualConfig::trainSpeed),
                TIME_OF_DAY_CODEC.optionalFieldOf("time_of_day", TrainWorldComponent.TimeOfDay.DAY).forGetter(VisualConfig::timeOfDay)
        ).apply(instance, VisualConfig::new));
    }

    public record AmbienceConfig(boolean requireTrainMoving, Optional<String> insideSound, Optional<String> outsideSound) {
        public static final String DEFAULT_INSIDE_SOUND = "wathe:ambient.train.inside";
        public static final String DEFAULT_OUTSIDE_SOUND = "wathe:ambient.train.outside";
        public static final AmbienceConfig DEFAULT = new AmbienceConfig(true, Optional.of(DEFAULT_INSIDE_SOUND), Optional.of(DEFAULT_OUTSIDE_SOUND));

        public static final Codec<AmbienceConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("require_train_moving", true).forGetter(AmbienceConfig::requireTrainMoving),
                Codec.STRING.optionalFieldOf("inside_sound", DEFAULT_INSIDE_SOUND).xmap(
                        s -> s.isEmpty() ? Optional.<String>empty() : Optional.of(s),
                        opt -> opt.orElse("")
                ).forGetter(AmbienceConfig::insideSound),
                Codec.STRING.optionalFieldOf("outside_sound", DEFAULT_OUTSIDE_SOUND).xmap(
                        s -> s.isEmpty() ? Optional.<String>empty() : Optional.of(s),
                        opt -> opt.orElse("")
                ).forGetter(AmbienceConfig::outsideSound)
        ).apply(instance, AmbienceConfig::new));
    }

    public record InteractionBlacklistConfig(List<String> blocks, List<String> blockTags) {
        public static final InteractionBlacklistConfig DEFAULT = new InteractionBlacklistConfig(List.of(), List.of());

        public static final Codec<InteractionBlacklistConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("blocks", List.of()).forGetter(InteractionBlacklistConfig::blocks),
                Codec.STRING.listOf().optionalFieldOf("block_tags", List.of()).forGetter(InteractionBlacklistConfig::blockTags)
        ).apply(instance, InteractionBlacklistConfig::new));

        public boolean isBlacklisted(Block block) {
            Identifier blockId = Registries.BLOCK.getId(block);
            for (String id : blocks) {
                if (blockId.toString().equals(id)) {
                    return true;
                }
            }
            for (String tagName : blockTags) {
                Identifier tagId = Identifier.tryParse(tagName);
                if (tagId != null && block.getDefaultState().isIn(TagKey.of(RegistryKeys.BLOCK, tagId))) {
                    return true;
                }
            }
            return false;
        }

        public Set<Block> getBlacklistedBlocks() {
            Set<Block> result = new HashSet<>();
            for (String id : blocks) {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier != null) {
                    result.add(Registries.BLOCK.get(identifier));
                }
            }
            for (String tagName : blockTags) {
                Identifier tagId = Identifier.tryParse(tagName);
                if (tagId != null) {
                    Registries.BLOCK.iterateEntries(TagKey.of(RegistryKeys.BLOCK, tagId)).forEach(entry -> result.add(entry.value()));
                }
            }
            return result;
        }
    }

    public record SpecialRolesConfig(List<String> enabledRoles) {
        public static final SpecialRolesConfig DEFAULT = new SpecialRolesConfig(List.of());

        public static final Codec<SpecialRolesConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("enabled_roles", List.of()).forGetter(SpecialRolesConfig::enabledRoles)
        ).apply(instance, SpecialRolesConfig::new));
    }

    public static final Codec<MapEnhancementsConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RoomConfig.CODEC.listOf().optionalFieldOf("rooms", List.of()).forGetter(MapEnhancementsConfiguration::rooms),
            SceneryConfig.CODEC.optionalFieldOf("scenery").forGetter(MapEnhancementsConfiguration::scenery),
            VisibilityConfig.CODEC.optionalFieldOf("visibility").forGetter(MapEnhancementsConfiguration::visibility),
            FogConfig.CODEC.optionalFieldOf("fog").forGetter(MapEnhancementsConfiguration::fog),
            CameraShakeConfig.CODEC.optionalFieldOf("camera_shake").forGetter(MapEnhancementsConfiguration::cameraShake),
            SnowParticlesConfig.CODEC.optionalFieldOf("snow_particles").forGetter(MapEnhancementsConfiguration::snowParticles),
            InteractionBlacklistConfig.CODEC.optionalFieldOf("interaction_blacklist").forGetter(MapEnhancementsConfiguration::interactionBlacklist),
            GravityConfig.CODEC.optionalFieldOf("gravity").forGetter(MapEnhancementsConfiguration::gravity),
            MovementConfig.CODEC.optionalFieldOf("movement").forGetter(MapEnhancementsConfiguration::movement),
            JumpConfig.CODEC.optionalFieldOf("jump").forGetter(MapEnhancementsConfiguration::jump),
            VisualConfig.CODEC.optionalFieldOf("visual").forGetter(MapEnhancementsConfiguration::visual),
            AmbienceConfig.CODEC.optionalFieldOf("ambience").forGetter(MapEnhancementsConfiguration::ambience),
            SpecialRolesConfig.CODEC.optionalFieldOf("special_roles").forGetter(MapEnhancementsConfiguration::specialRoles)
    ).apply(instance, MapEnhancementsConfiguration::new));

    public SceneryConfig getSceneryOrDefault() {
        return scenery.orElse(SceneryConfig.DEFAULT);
    }

    public VisibilityConfig getVisibilityOrDefault() {
        return visibility.orElse(VisibilityConfig.DEFAULT);
    }

    public FogConfig getFogOrDefault() {
        return fog.orElse(FogConfig.DEFAULT);
    }

    public CameraShakeConfig getCameraShakeOrDefault() {
        return cameraShake.orElse(CameraShakeConfig.DEFAULT);
    }

    public SnowParticlesConfig getSnowParticlesOrDefault() {
        return snowParticles.orElse(SnowParticlesConfig.DEFAULT);
    }

    public InteractionBlacklistConfig getInteractionBlacklistOrDefault() {
        return interactionBlacklist.orElse(InteractionBlacklistConfig.DEFAULT);
    }

    public GravityConfig getGravityOrDefault() {
        return gravity.orElse(GravityConfig.DEFAULT);
    }

    public MovementConfig getMovementOrDefault() {
        return movement.orElse(MovementConfig.DEFAULT);
    }

    public JumpConfig getJumpOrDefault() {
        return jump.orElse(JumpConfig.DEFAULT);
    }

    public VisualConfig getVisualOrDefault() {
        return visual.orElse(VisualConfig.DEFAULT);
    }

    public AmbienceConfig getAmbienceOrDefault() {
        return ambience.orElse(AmbienceConfig.DEFAULT);
    }

    public SpecialRolesConfig getSpecialRolesOrDefault() {
        return specialRoles.orElse(SpecialRolesConfig.DEFAULT);
    }

    public int getRoomCount() {
        return rooms.size();
    }

    public Optional<RoomConfig> getRoomConfig(int roomNumber) {
        if (roomNumber < 1 || roomNumber > rooms.size()) {
            return Optional.empty();
        }
        return Optional.of(rooms.get(roomNumber - 1));
    }

    public Optional<RoomConfig.SpawnPoint> getSpawnPointForPlayer(int roomNumber, int playerIndexInRoom) {
        return getRoomConfig(roomNumber)
                .filter(room -> !room.spawnPoints().isEmpty())
                .map(room -> room.getSpawnPoint(playerIndexInRoom));
    }

    public int getTotalCapacity() {
        return rooms.stream().mapToInt(RoomConfig::getMaxPlayers).sum();
    }
}
