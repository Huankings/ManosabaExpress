package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.slot.FurnaceOutputSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FurnaceOutputSlot.class)
public class FurnaceOutputSlotMixin {
    /**
     * “烤吃的”任务需要在玩家真正把熟食从结果槽里拿走时才算完成。
     *
     * <p>因此这里同时要求：
     * 1. 逻辑发生在服务端；
     * 2. 取出者确实是服务器玩家；
     * 3. 当前界面是普通熔炉或烟熏炉，不把高炉算进去；
     * 4. 取出的结果本身是可食用物品。
     *
     * <p>这样可以精确对应你的需求，避免把非食物产物或高炉结果误判为完成任务。
     */
    @Inject(method = "onTakeItem", at = @At("RETURN"))
    private void wathe$completeCookTask(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || player.getWorld().isClient) {
            return;
        }

        if (!(player.currentScreenHandler instanceof FurnaceScreenHandler || player.currentScreenHandler instanceof SmokerScreenHandler)) {
            return;
        }

        if (stack.get(DataComponentTypes.FOOD) == null) {
            return;
        }

        PlayerMoodComponent.KEY.get(serverPlayer).takeCookedFood();
    }
}
