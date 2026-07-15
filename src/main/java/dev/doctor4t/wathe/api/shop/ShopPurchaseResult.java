package dev.doctor4t.wathe.api.shop;

/**
 * 购买处理器的返回结果。
 *
 * <p>失败被拆成“显示通用失败提示”和“静默失败”两种，是为了兼容特殊商品：
 * 例如工程师电力恢复系统会自己提示“当前没有停电”，这时 Wathe 仍播放失败音效，
 * 但不会再额外覆盖一条普通的“购买失败”。</p>
 */
public enum ShopPurchaseResult {
    SUCCESS(true, false),
    FAIL_SHOW_MESSAGE(false, true),
    FAIL_SILENT(false, false);

    private final boolean successful;
    private final boolean notifyFailure;

    ShopPurchaseResult(boolean successful, boolean notifyFailure) {
        this.successful = successful;
        this.notifyFailure = notifyFailure;
    }

    public boolean successful() {
        return this.successful;
    }

    public boolean shouldNotifyFailure() {
        return this.notifyFailure;
    }
}
