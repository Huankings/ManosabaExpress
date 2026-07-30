package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PlayerInventory.class)
public class PlayerInventoryMixin {
    @Shadow
    @Final
    public PlayerEntity player;

    @WrapMethod(method = "scrollInHotbar")
    private void wathe$invalid(double scrollAmount, @NotNull Operation<Void> original) {
        int oldSlot = this.player.getInventory().selectedSlot;
        original.call(scrollAmount);
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(this.player);
        if (component.isPsychoActive()
                && component.getProfile().lockHotbar()
                && PsychoModeApi.isLockedItem(this.player, this.player.getInventory().getStack(oldSlot))
                && !PsychoModeApi.isLockedItem(this.player, this.player.getInventory().getStack(this.player.getInventory().selectedSlot))
        ) this.player.getInventory().selectedSlot = oldSlot;
    }
}
