package dev.doctor4t.wathe.api.combat;

/** 服务端匕首刺击处理器的结果。 */
public enum KnifeStabResult {
    /** 当前处理器不关心本次刺击，继续询问低优先级处理器或执行 Wathe 默认逻辑。 */
    PASS,
    /** 扩展已经完整处理本次刺击，Wathe 不再执行默认玩家刺杀。 */
    HANDLED,
    /** 明确取消本次刺击，不执行任何后续处理器或 Wathe 默认逻辑。 */
    CANCEL
}
