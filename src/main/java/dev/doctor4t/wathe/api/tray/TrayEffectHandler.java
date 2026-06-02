package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 托盘附加效果处理器。
 *
 * <p>它专门服务于“放进托盘后，消费者会获得某种特殊效果”的扩展逻辑，
 * 例如防御试剂、幻觉试剂、后续其它扩展职业的专属药剂等。</p>
 *
 * <p>和 wathe 原生毒药不同，这类效果不应该硬塞进 PlayerPoisonComponent。
 * 因此这里把“放置托盘 / 拿取物品 / 实际食用或饮用”的生命周期拆成显式接口，
 * 让扩展模组可以稳定挂接自己的行为。</p>
 */
public interface TrayEffectHandler {
    /**
     * 此托盘效果的唯一逻辑 ID。
     */
    Identifier effectId();

    /**
     * 用来把效果放进托盘的物品。
     */
    Item additiveItem();

    /**
     * 是否允许把当前效果放入托盘。
     *
     * <p>默认策略很保守：托盘上既没有真实毒药，也没有其它扩展效果时才能放入。
     * 需要“覆盖旧效果 / 清除毒药”的模组可以自行重写。</p>
     */
    default boolean canApply(BeveragePlateBlockEntity plate, ServerPlayerEntity player, ItemStack heldStack) {
        return plate.getPoisoner() == null && plate.getTrayEffect() == null;
    }

    /**
     * 真正执行“把效果下到托盘里”。
     *
     * <p>大多数扩展效果都可以直接调用
     * {@link TrayEffectRegistry#applyStandardEffect(ServerPlayerEntity, ItemStack, BeveragePlateBlockEntity, BlockPos, Identifier, boolean, boolean)}，
     * 只在需要特殊覆盖规则时重写额外逻辑。</p>
     */
    void applyToTray(BeveragePlateBlockEntity plate, ServerPlayerEntity player, ItemStack heldStack, BlockPos pos);

    /**
     * 托盘里的物品被玩家拿起时调用。
     *
     * <p>这里既可以往拿出的 ItemStack 上补额外数据，也可以往回放 extra 里补字段。</p>
     */
    default void onTakeFromTray(ServerPlayerEntity player, ItemStack takenStack, @Nullable UUID applierUuid, NbtCompound replayExtra) {
    }

    /**
     * 玩家真正食用 / 饮用带有效果的物品时调用。
     *
     * <p>consumeType 与 wathe 回放里的 consume_type 保持一致，常见值有：
     * eat_food / drink_cocktail / drink_potion。</p>
     */
    void onConsume(ServerPlayerEntity player, ItemStack consumedSnapshot, String consumeType, @Nullable UUID applierUuid);
}
