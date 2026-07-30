package dev.doctor4t.wathe.api.death;

/**
 * 死亡流程拦截结果。
 */
public enum DeathDecision {
    /**
     * 当前拦截器不决定结果，继续交给低优先级拦截器或 Wathe 默认死亡流程。
     */
    PASS,
    /**
     * 取消本次死亡请求。
     *
     * <p>用于“死亡被某机制吞掉/改写”的场景；返回后 Wathe 不会继续切旁观、记录死亡或生成尸体。</p>
     */
    CANCEL
}
