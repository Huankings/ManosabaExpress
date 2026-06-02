package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.api.bed.BedEffectHandler;
import dev.doctor4t.wathe.api.bed.BedEffectRegistry;
import dev.doctor4t.wathe.block_entity.TrimmedBedBlockEntity;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * wathe 本体自带的床效果注册入口。
 *
 * <p>目前这里只有原版蝎子，
 * 但后续本体如果还要继续新增“塞床里”的物品，也都应该统一注册在这里。</p>
 */
public final class WatheBedEffects {
    private WatheBedEffects() {
    }

    public static void register() {
        BedEffectRegistry.register(new BedEffectHandler() {
            @Override
            public Identifier effectId() {
                return net.minecraft.registry.Registries.ITEM.getId(WatheItems.SCORPION);
            }

            @Override
            public net.minecraft.item.Item additiveItem() {
                return WatheItems.SCORPION;
            }

            @Override
            public void applyToBed(TrimmedBedBlockEntity bed, ServerPlayerEntity player, ItemStack heldStack, BlockPos pos) {
                BedEffectRegistry.applyStandardEffect(player, heldStack, bed, pos, effectId(), false);
            }

            @Override
            public boolean onSleepTrigger(ServerPlayerEntity player, TrimmedBedBlockEntity bed, @Nullable UUID applierUuid) {
                PoisonUtils.applyScorpionBedEffect(player, applierUuid);
                return true;
            }
        });
    }
}
