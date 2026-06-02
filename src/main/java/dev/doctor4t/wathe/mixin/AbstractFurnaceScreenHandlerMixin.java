package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractFurnaceScreenHandler.class)
public abstract class AbstractFurnaceScreenHandlerMixin {
    /**
     * 原版熔炉系界面的结果槽索引固定为 2：
     * 0 是输入槽，1 是燃料槽，2 才是产物槽。
     *
     * <p>这里把索引单独抽成常量，方便后续阅读和调整，
     * 也能明确说明我们只想拦截“从结果槽 shift+点击快速取出”的情况。
     */
    private static final int WATHE_OUTPUT_SLOT_INDEX = 2;

    /**
     * “烤吃的”任务在普通鼠标拿取时，会经过 {@code FurnaceOutputSlot#onTakeItem}。
     * 但玩家使用 shift+左键快速转移结果槽物品时，原版会走 screen handler 的
     * {@code quickMove} 分支，因此仅监听结果槽本身会漏掉这一种操作。
     *
     * <p>这里在 quickMove 返回后补一层兼容判定，只要满足以下条件就视为完成任务：
     * 1. 当前操作发生在服务端；
     * 2. 玩家确实是从结果槽（索引 2）进行 shift+取物；
     * 3. 当前界面是普通熔炉或烟熏炉，不把高炉算进去；
     * 4. 最终快速转移出来的物品是可食用食物。
     *
     * <p>这样一来，无论是鼠标点着拿，还是 shift+左键快捷取出熟食，
     * “烤吃的”任务都能稳定完成。
     */
    @Inject(method = "quickMove", at = @At("RETURN"))
    private void wathe$completeCookTaskOnQuickMove(PlayerEntity player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot != WATHE_OUTPUT_SLOT_INDEX) {
            return;
        }

        if (!(player instanceof ServerPlayerEntity serverPlayer) || player.getWorld().isClient) {
            return;
        }

        Object screenHandler = this;
        if (!(screenHandler instanceof FurnaceScreenHandler) && !(screenHandler instanceof SmokerScreenHandler)) {
            return;
        }

        ItemStack movedStack = cir.getReturnValue();
        if (movedStack.isEmpty() || movedStack.get(DataComponentTypes.FOOD) == null) {
            return;
        }

        PlayerMoodComponent.KEY.get(serverPlayer).takeCookedFood();
    }
}
