package dev.doctor4t.wathe.api.bed;

import dev.doctor4t.wathe.block_entity.TrimmedBedBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 床附加效果处理器。
 *
 * <p>它专门服务于“把某种物品塞进床里，后续在睡觉结算时触发”的扩展逻辑，
 * 例如 wathe 原版蝎子、noellesroles 的定时炸弹等。</p>
 */
public interface BedEffectHandler {
    /**
     * 此床效果的唯一逻辑 ID。
     */
    Identifier effectId();

    /**
     * 用来把效果放进床里的物品。
     */
    Item additiveItem();

    /**
     * 是否允许把当前效果放入床里。
     *
     * <p>默认规则是床里当前没有任何其它附加效果。</p>
     */
    default boolean canApply(TrimmedBedBlockEntity bed, ServerPlayerEntity player, ItemStack heldStack) {
        return bed.getBedEffect() == null;
    }

    /**
     * 真正执行“把效果塞进床里”。
     */
    void applyToBed(TrimmedBedBlockEntity bed, ServerPlayerEntity player, ItemStack heldStack, BlockPos pos);

    /**
     * 玩家睡觉后 40 tick 触发床效果时调用。
     *
     * @return 是否应当消耗掉床里的这层效果
     */
    boolean onSleepTrigger(ServerPlayerEntity player, TrimmedBedBlockEntity bed, @Nullable UUID applierUuid);
}
