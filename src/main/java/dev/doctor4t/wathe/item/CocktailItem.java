package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.TrayEffectUtils;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

public class CocktailItem extends Item {
    public CocktailItem(Item.Settings settings) {
        super(settings);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        /**
         * 回放必须记录“喝下去之前”的鸡尾酒快照。
         * 因为原版 finishUsing 在生存模式下会直接消耗当前物品栈，
         * 如果等 super.finishUsing(...) 之后再去读取 stack.getName()，
         * 这里就很容易只剩下空气，最终回放会错误显示成“饮用了 [空气]”。
         *
         * 这里复制一份快照给回放系统使用，只保留展示所需的物品类型、名称和数据组件，
         * 这样无论后续原栈如何变化，回放都能稳定显示真实喝下去的鸡尾酒名称。
         */
        ItemStack replaySnapshot = stack.copy();
        ItemStack result = super.finishUsing(stack, world, user);
        if (user instanceof ServerPlayerEntity serverPlayerEntity) {
            Criteria.CONSUME_ITEM.trigger(serverPlayerEntity, replaySnapshot);
            serverPlayerEntity.incrementStat(Stats.USED.getOrCreateStat(this));
            PlayerMoodComponent.KEY.get(serverPlayerEntity).drinkCocktail();

            /**
             * 扩展托盘效果（例如防御试剂 / 幻觉试剂）的消费记录，已经在 PlayerEntity.eatFood
             * 里按统一入口处理过了。这里如果再次记录，会把同一杯鸡尾酒播报两次。
             */
            if (!TrayEffectUtils.hasTrayEffect(replaySnapshot)) {
                String poisoner = replaySnapshot.getOrDefault(WatheDataComponentTypes.POISONER, null);
                GameRecordManager.recordConsumeItem(
                        serverPlayerEntity,
                        replaySnapshot,
                        "drink_cocktail",
                        poisoner != null,
                        poisoner == null ? null : java.util.UUID.fromString(poisoner),
                        null
                );
            }
        }
        return result;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return 40;
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.DRINK;
    }

    @Override
    public SoundEvent getEatSound() {
        return SoundEvents.ENTITY_GENERIC_DRINK;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        return ItemUsage.consumeHeldItem(world, user, hand);
    }
}
