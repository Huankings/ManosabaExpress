package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.UUID;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    public abstract @Nullable Entity getOwner();

    @Shadow
    private @Nullable UUID throwerUuid;

    @Shadow
    public abstract ItemStack getStack();

    @WrapMethod(method = "onPlayerCollision")
    public void wathe$preventGunPickup(PlayerEntity player, Operation<Void> original) {
        ItemStack stack = this.getStack().copy();
        int countBefore = stack.getCount();

        if (player.isCreative() || !this.getStack().isIn(WatheItemTags.GUNS) || (GameWorldComponent.KEY.get(player.getWorld()).isInnocent(player) && !player.equals(this.getOwner()) && !player.getInventory().contains(itemStack -> itemStack.isIn(WatheItemTags.GUNS)))) {
            original.call(player);

            if ((Object) this instanceof ItemEntity itemEntity
                    && player instanceof ServerPlayerEntity serverPlayer
                    && (itemEntity.isRemoved() || itemEntity.getStack().getCount() < countBefore)) {
                GameRecordManager.recordItemPickup(serverPlayer, stack, countBefore);
            }
        }
    }
}
