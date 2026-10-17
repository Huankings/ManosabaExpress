package dev.doctor4t.wathe.api.item;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端处理普通掉落物拾取时提供给扩展的上下文。
 *
 * @param player             尝试拾取的服务端玩家
 * @param itemEntity         当前掉落物实体
 * @param stack              进入判定时的物品堆快照；只用于读取，不会代替实体内的真实堆栈
 * @param owner              原版 ItemEntity 当前解析到的 owner 实体，可能为 null
 * @param game               当前对局组件
 * @param watheDefaultAllows 没有扩展覆盖时，Wathe 原有枪械拾取规则是否允许继续
 */
public record ItemPickupContext(
        @NotNull ServerPlayerEntity player,
        @NotNull ItemEntity itemEntity,
        @NotNull ItemStack stack,
        @Nullable Entity owner,
        @NotNull GameWorldComponent game,
        boolean watheDefaultAllows
) {
}
