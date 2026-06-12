package dev.doctor4t.wathe.config.datapack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * 一张可投票地图的入口。
 *
 * <p>这里不读取 WatheConfig，避免服务端数据包加载时初始化客户端配置类。
 * 人数上下限始终按 json 生效。</p>
 */
public record MapRegistryEntry(
        Identifier dimensionId,
        String displayName,
        Optional<String> description,
        MapEnhancementsConfiguration enhancements,
        int minPlayers,
        int maxPlayers
) {
    public static final Codec<MapRegistryEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(MapRegistryEntry::dimensionId),
            Codec.STRING.fieldOf("display_name").forGetter(MapRegistryEntry::displayName),
            Codec.STRING.optionalFieldOf("description").forGetter(MapRegistryEntry::description),
            MapEnhancementsConfiguration.CODEC.optionalFieldOf("enhancements", MapEnhancementsConfiguration.EMPTY).forGetter(MapRegistryEntry::enhancements),
            Codec.INT.optionalFieldOf("min_players", 0).forGetter(MapRegistryEntry::minPlayers),
            Codec.INT.optionalFieldOf("max_players", 100).forGetter(MapRegistryEntry::maxPlayers)
    ).apply(instance, MapRegistryEntry::new));

    public boolean isEligible(int playerCount) {
        return playerCount >= minPlayers && playerCount <= maxPlayers;
    }
}
