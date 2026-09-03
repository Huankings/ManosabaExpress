package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/** 客户端托盘粒子 provider 的上下文。 */
public record TrayParticleContext(ClientWorld world, BlockPos pos, BeveragePlateBlockEntity plate, ClientPlayerEntity viewer) {
}
