package dev.doctor4t.wathe.api.psycho;

/**
 * 疯魔护盾规则的返回值。
 */
public enum PsychoShieldResult {
    /**
     * 当前规则不处理，交给后续规则或 Wathe 默认护盾层数逻辑。
     */
    PASS,
    /**
     * 本次死亡被疯魔护盾挡住。
     */
    BLOCK,
    /**
     * 本次死亡绕过疯魔护盾，并结束当前疯魔状态。
     */
    BYPASS
}
