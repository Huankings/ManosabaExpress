package dev.doctor4t.wathe.record;

/**
 * 主模组内置的对局记录事件类型。
 *
 * <p>大部分“真正显示成什么句子”的逻辑不写死在这里，
 * 而是交给 replay 包的格式化器做二次分发。</p>
 */
public final class GameRecordTypes {
    private GameRecordTypes() {
    }

    public static final String MATCH_START = "match_start";
    public static final String ROLE_ASSIGNED = "role_assigned";
    public static final String ROLE_CHANGED = "role_changed";
    public static final String SHOP_PURCHASE = "shop_purchase";
    public static final String ITEM_USE = "item_use";
    public static final String ITEM_HIT = "item_hit";
    public static final String ITEM_PICKUP = "item_pickup";
    public static final String PLATTER_TAKE = "platter_take";
    public static final String CONSUME_ITEM = "consume_item";
    public static final String PLAYER_POISONED = "player_poisoned";
    public static final String DEATH = "death";
    public static final String SHIELD_BLOCKED = "shield_blocked";
    public static final String SKILL_USE = "skill_use";
    public static final String GLOBAL_EVENT = "global_event";
    public static final String DOOR_INTERACTION = "door_interaction";
    public static final String TASK_COMPLETE = "task_complete";
    public static final String MATCH_END = "match_end";
    public static final String PLAYER_RESULT = "player_result";
    public static final String PLAYER_JOIN = "player_join";
    public static final String PLAYER_LEAVE = "player_leave";
}
