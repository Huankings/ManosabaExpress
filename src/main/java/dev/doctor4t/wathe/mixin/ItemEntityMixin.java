package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.api.item.ItemPickupApi;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    public abstract ItemStack getStack();

    @WrapMethod(method = "onPlayerCollision")
    public void wathe$preventGunPickup(PlayerEntity player, Operation<Void> original) {
        ItemStack stack = this.getStack().copy();
        int countBefore = stack.getCount();

        /*
         * ItemEntity#onPlayerCollision 同时存在于两端，但物品真正进背包、统计和进度准则
         * 都由服务端决定。因此 API 只在 ServerPlayerEntity 上作为权威判定；客户端继续
         * 进入原方法，避免因未同步的扩展状态造成本地表现差异。
         */
        boolean allowed = !(player instanceof ServerPlayerEntity serverPlayer)
                || ItemPickupApi.canPickup(serverPlayer, (ItemEntity) (Object) this);
        if (allowed) {
            original.call(player);

            if ((Object) this instanceof ItemEntity itemEntity
                    && player instanceof ServerPlayerEntity serverPlayer
                    && (itemEntity.isRemoved() || itemEntity.getStack().getCount() < countBefore)) {
                GameRecordManager.recordItemPickup(serverPlayer, stack, countBefore);
            }
        }
    }
}
