package dev.doctor4t.wathe.api.psycho;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * 疯魔 profile 对物品的动态判定。
 *
 * <p>profile 自带的授予物品会由 Wathe 自动打上 {@code PSYCHO_GRANTED_PROFILE} 标记；
 * 只有当职业需要把“已存在的某个物品”也视为疯魔武器/锁定物品时，才需要注册这个谓词。</p>
 */
@FunctionalInterface
public interface PsychoItemPredicate {
    boolean test(PlayerEntity player, ItemStack stack);
}
