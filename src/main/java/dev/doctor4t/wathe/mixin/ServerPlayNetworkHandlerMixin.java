package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.util.BatAttackCooldownPreserver;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @WrapMethod(method = "onUpdateSelectedSlot")
    private void wathe$invalid(UpdateSelectedSlotC2SPacket packet, @NotNull Operation<Void> original) {
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(this.player);
        if (component.getPsychoTicks() > 0 && !this.player.getInventory().getStack(packet.getSelectedSlot()).isOf(WatheItems.BAT))
            return;
        original.call(packet);
    }

    @Inject(method = "onPlayerInteractBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactBlock(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;Lnet/minecraft/util/hit/BlockHitResult;)Lnet/minecraft/util/ActionResult;"))
    private void wathe$preserveBatCooldownOnBlockInteract(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        this.wathe$preserveBatCooldownForRightClick(packet.getHand());
    }

    @Inject(method = "onPlayerInteractItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;interactItem(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/ActionResult;"))
    private void wathe$preserveBatCooldownOnItemInteract(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        this.wathe$preserveBatCooldownForRightClick(packet.getHand());
    }

    @Inject(method = "onPlayerInteractEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;handle(Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket$Handler;)V"))
    private void wathe$preserveBatCooldownOnEntityInteract(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void interact(Hand hand) {
                wathe$preserveBatCooldownForRightClick(hand);
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
                wathe$preserveBatCooldownForRightClick(hand);
            }

            @Override
            public void attack() {
            }
        });
    }

    private void wathe$preserveBatCooldownForRightClick(Hand hand) {
        // 只标记右键交互触发的球棒挥手；真正左键攻击仍走原版攻击冷却复位逻辑。
        ((BatAttackCooldownPreserver) this.player).wathe$preserveNextBatInteractionSwing(hand);
    }
}
