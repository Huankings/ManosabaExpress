package dev.doctor4t.wathe.api.bed;

import dev.doctor4t.wathe.block_entity.TrimmedBedBlockEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 客户端床粒子 provider 的上下文。
 *
 * <p>床效果本身仍由服务端 {@link BedEffectRegistry} 负责裁定和同步；
 * 这里仅提供客户端生成视觉粒子所需的世界、坐标、床方块实体和观察者，
 * 避免扩展为了读取床效果而直接注入 Wathe 的床方块实体 tick。</p>
 */
public record BedParticleContext(
        ClientWorld world,
        BlockPos pos,
        TrimmedBedBlockEntity bed,
        ClientPlayerEntity viewer
) {
}
