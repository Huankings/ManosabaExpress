package dev.doctor4t.wathe.game;

import org.jetbrains.annotations.Nullable;

/** 共用的开局补贴计算：预览和发放采用相同规则，强制职业和覆盖余数都不改变自然杀手名额。 */
public record KillerStartingEconomy(int playerCount, int dividend, int coinsPerRemainder,
                                   @Nullable Integer remainderOverride) {
    public int naturalKillerSlots() {
        return playerCount / dividend;
    }

    public int naturalRemainder() {
        return playerCount % dividend;
    }

    public int effectiveRemainder() {
        return remainderOverride != null ? remainderOverride : naturalRemainder();
    }

    /** 先用 long 计算补贴，再按余额字段的上限截断，避免大的调试数值溢出为负数。 */
    public long bonusCoins() {
        return (long) effectiveRemainder() * coinsPerRemainder;
    }

    public int applyToBalance(int balance) {
        return (int) Math.min(Integer.MAX_VALUE, balance + bonusCoins());
    }
}
