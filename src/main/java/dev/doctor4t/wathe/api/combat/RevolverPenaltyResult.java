package dev.doctor4t.wathe.api.combat;

/**
 * 左轮命中“无辜目标”后的惩罚判定。
 *
 * <p>这里的惩罚不只是反火，也包括 Wathe 默认的掉枪和清空心情值。
 * 因此扩展如果希望某次射击完全不按“误伤好人”处理，应返回 {@link #SKIP}。</p>
 */
public enum RevolverPenaltyResult {
    /**
     * 当前规则不决定结果，继续交给低优先级规则或 Wathe 默认阵营判定。
     */
    PASS,
    /**
     * 强制按“命中无辜者”处理，继续执行反火/掉枪/清空心情。
     */
    APPLY,
    /**
     * 跳过整段“误伤无辜者惩罚”，目标仍可被正常击杀。
     */
    SKIP
}
