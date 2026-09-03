package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** 托盘取物规则收到的只读上下文。 */
public record TrayTakeContext(ServerPlayerEntity player, BeveragePlateBlockEntity plate, ItemStack candidate) {
}
