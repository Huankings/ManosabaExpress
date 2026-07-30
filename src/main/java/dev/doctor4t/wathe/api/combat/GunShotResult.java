package dev.doctor4t.wathe.api.combat;

/**
 * 枪击请求的处理结果。
 *
 * <p>扩展接管枪击时请明确返回语义：</p>
 * <p>1. {@link #PASS}：本处理器不接管，继续交给低优先级处理器或 Wathe 默认枪击逻辑；</p>
 * <p>2. {@link #CANCEL}：吞掉本次请求，不播放默认枪声、不补枪口包、不设置默认冷却；</p>
 * <p>3. {@link #HANDLED}：扩展已经完整处理本次开火，Wathe 不再执行默认逻辑。</p>
 */
public enum GunShotResult {
    /**
     * 不处理，继续执行低优先级 handler 或 Wathe 默认开火逻辑。
     */
    PASS,
    /**
     * 取消本次请求；常用于“命中特殊实体后吞掉开火”，不播放默认声音/枪口/冷却。
     */
    CANCEL,
    /**
     * 扩展已经完整处理枪声、命中、击杀、枪口和冷却，Wathe 不再补默认逻辑。
     */
    HANDLED
}
