package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Wathe 默认左轮“误伤好人惩罚”的判定上下文。
 *
 * @param shooter                 开火玩家
 * @param target                  被服务端确认命中的玩家
 * @param stack                   开火时手持的物品堆栈
 * @param game                    当前世界对局组件
 * @param targetNormallyInnocent  Wathe 原始 {@code game.isInnocent(target)} 结果
 */
public record RevolverPenaltyContext(@NotNull PlayerEntity shooter,
                                     @NotNull PlayerEntity target,
                                     @NotNull ItemStack stack,
                                     @NotNull GameWorldComponent game,
                                     boolean targetNormallyInnocent) {
}
