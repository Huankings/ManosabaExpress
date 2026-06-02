package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingBobberEntity.class)
public class FishingBobberEntityMixin {
    /**
     * 原版收杆返回值为 1 时，表示玩家确实钓起了战利品。
     * 其它返回值代表勾到实体、鱼漂落地等情况，这里都不算作“钓鱼”任务完成。
     */
    @Inject(method = "use", at = @At("RETURN"))
    private void wathe$completeFishTask(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValue() != 1) {
            return;
        }

        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (self.getWorld().isClient || !(self.getPlayerOwner() instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        PlayerMoodComponent.KEY.get(serverPlayer).catchFish();
        GameRecordManager.recordGlobalEvent(serverPlayer.getServerWorld(), Wathe.id("fishing_rod_used"), serverPlayer, null);
    }
}
