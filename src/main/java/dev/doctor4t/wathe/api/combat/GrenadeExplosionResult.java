package dev.doctor4t.wathe.api.combat;

/** 服务端手雷碰撞/爆炸处理器的结果。 */
public enum GrenadeExplosionResult {
    /** 当前处理器可执行附加逻辑，但仍继续询问后续处理器并保留 Wathe 默认爆炸。 */
    PASS,
    /** 扩展已经完成本次爆炸表现与结算，跳过 Wathe 默认爆炸。 */
    HANDLED,
    /** 明确取消本次爆炸，跳过后续处理器与 Wathe 默认爆炸。 */
    CANCEL
}
