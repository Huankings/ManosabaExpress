package dev.doctor4t.wathe.api.item;

/** 扩展对普通 {@code ItemEntity} 拾取请求的判定结果。 */
public enum ItemPickupResult {
    /** 继续询问低优先级规则；全部 PASS 时使用 Wathe 默认拾取规则。 */
    PASS,
    /** 允许进入原版拾取流程，可用于覆盖 Wathe 默认的枪械职业限制。 */
    ALLOW,
    /** 拒绝本次拾取，不调用原版 {@code ItemEntity#onPlayerCollision}。 */
    DENY
}
