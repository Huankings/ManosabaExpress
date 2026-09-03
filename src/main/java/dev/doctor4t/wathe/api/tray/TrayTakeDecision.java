package dev.doctor4t.wathe.api.tray;

/**
 * 托盘取物规则的分组决策。
 *
 * <p>DISTINCT_TYPES 按候选物品类型数量计算上限，适合厨师/酒保的“三种不同物品”；
 * TOTAL_COUNT 按玩家当前持有数量计算上限，适合服务员的“同类最多两份”。</p>
 */
public record TrayTakeDecision(String groupId, int limit, Mode mode) {
    public TrayTakeDecision {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        limit = Math.max(0, limit);
        if (mode == null) {
            mode = Mode.DISTINCT_TYPES;
        }
    }

    public enum Mode {
        DISTINCT_TYPES,
        TOTAL_COUNT
    }
}
