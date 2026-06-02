package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PotionItem.class)
public class PotionItemMixin {
    /**
     * 原版普通药水喝完后会进入 finishUsing。
     * 这里必须在 HEAD 处理，而不能放在 TAIL：
     * 1. 单瓶药水在生存模式下会被原版提前 return 成玻璃瓶，TAIL 不一定能执行到；
     * 2. 原版会在方法中途消耗原来的药水 ItemStack，等到后面再判断 stack.isOf(Items.POTION) 可能已经变成空栈。
     *
     * 因此在方法开头、药水栈仍然保持 minecraft:potion 的时候完成任务。
     * 这里只认 minecraft:potion，不把喷溅药水、滞留药水等其它药水道具算进去。
     */
    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void wathe$completePotionTask(ItemStack stack, World world, LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
        if (world.isClient || !(user instanceof ServerPlayerEntity serverPlayer) || !stack.isOf(Items.POTION)) {
            return;
        }

        PlayerMoodComponent.KEY.get(serverPlayer).drinkPotion();
    }
}
