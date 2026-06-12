package dev.doctor4t.wathe.config.datapack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * 数据包中的房间配置。
 *
 * <p>一个房间可以有多个出生点，人数超过出生点数量时会循环使用。
 * 这样地图作者既能做单人房，也能做多人共用的大房间。</p>
 */
public record RoomConfig(
        List<SpawnPoint> spawnPoints,
        Optional<Integer> maxPlayers,
        Optional<String> name
) {
    public record SpawnPoint(
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        public static final Codec<SpawnPoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("x").forGetter(SpawnPoint::x),
                Codec.DOUBLE.fieldOf("y").forGetter(SpawnPoint::y),
                Codec.DOUBLE.fieldOf("z").forGetter(SpawnPoint::z),
                Codec.FLOAT.fieldOf("yaw").forGetter(SpawnPoint::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(SpawnPoint::pitch)
        ).apply(instance, SpawnPoint::new));

        public Vec3d toVec3d() {
            return new Vec3d(x, y, z);
        }
    }

    public static final Codec<RoomConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            SpawnPoint.CODEC.listOf().fieldOf("spawn_points").forGetter(RoomConfig::spawnPoints),
            Codec.INT.optionalFieldOf("max_players").forGetter(RoomConfig::maxPlayers),
            Codec.STRING.optionalFieldOf("name").forGetter(RoomConfig::name)
    ).apply(instance, RoomConfig::new));

    public String getName(int roomNumber) {
        return name.orElse("Room " + roomNumber);
    }

    public int getMaxPlayers() {
        return maxPlayers.orElse(spawnPoints.size());
    }

    public SpawnPoint getSpawnPoint(int playerIndex) {
        if (spawnPoints.isEmpty()) {
            throw new IllegalStateException("Room has no spawn points configured");
        }
        return spawnPoints.get(playerIndex % spawnPoints.size());
    }
}
