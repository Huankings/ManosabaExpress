package dev.doctor4t.wathe.api.death;

/**
 * Wathe 默认击杀收益是否发放的公开决策。
 *
 * <p>这里只控制 Wathe 默认的击杀金币/任务币；扩展自己的额外奖励应放在
 * {@link DeathApi#registerAfterAttempt} 或职业自己的死亡处理器里。</p>
 */
public enum KillerRewardResult {
    /**
     * 当前规则不决定默认收益，继续交给低优先级规则或 Wathe 原始判定。
     */
    PASS,
    /**
     * 强制发放 Wathe 默认击杀收益。
     */
    ALLOW,
    /**
     * 阻止 Wathe 默认击杀收益。
     */
    DENY
}
