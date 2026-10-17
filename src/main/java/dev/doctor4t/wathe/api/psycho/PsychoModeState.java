package dev.doctor4t.wathe.api.psycho;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * 一次已启动疯魔的可恢复运行态快照。
 *
 * <p>它用于“更高优先级的全场时刻临时暂停疯魔”这类场景：暂停会回收 profile 临时物品、
 * 停止音乐和实际机制，但不会记录疯魔结束；恢复时重新授予同一 profile 的临时物品并继续原剩余时间。
 * 该快照不包含玩家背包、物品冷却或其它职业组件，这些状态仍由调用方自己的组件 / 时间线管理。</p>
 */
public record PsychoModeState(
        @NotNull Identifier profileId,
        int remainingTicks,
        int maxTicks,
        int armour,
        int initialArmour
) {
    public PsychoModeState {
        remainingTicks = Math.max(1, remainingTicks);
        maxTicks = Math.max(remainingTicks, maxTicks);
        armour = Math.max(0, armour);
        initialArmour = Math.max(0, initialArmour);
    }
}
