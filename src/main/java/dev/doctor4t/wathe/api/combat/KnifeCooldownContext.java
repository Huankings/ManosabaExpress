package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Wathe 默认匕首刺杀成功进入冷却前的服务端上下文。
 *
 * @param attacker               发动匕首刺杀的玩家
 * @param target                 服务端已通过距离和可攻击校验的目标
 * @param stack                  服务端处理本次刺杀时的主手物品快照；只用于读取判定，不应修改
 * @param game                   当前对局组件
 * @param baseCooldown           Wathe 在扩展修正前的默认匕首冷却
 * @param defaultCooldownApplies Wathe 默认规则是否会真正写入冷却；创造模式和 Loose Ends 为 false
 */
public record KnifeCooldownContext(
        @NotNull ServerPlayerEntity attacker,
        @NotNull PlayerEntity target,
        @NotNull ItemStack stack,
        @NotNull GameWorldComponent game,
        int baseCooldown,
        boolean defaultCooldownApplies
) {
}
