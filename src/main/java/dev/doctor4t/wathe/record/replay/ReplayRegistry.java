package dev.doctor4t.wathe.record.replay;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 回放格式化器注册表。
 *
 * <p>主模组注册默认事件，扩展职业模组可额外注册：
 * 技能、物品使用、餐盘拿取、消耗物、全局事件等专属格式化器。</p>
 */
public final class ReplayRegistry {
    private ReplayRegistry() {
    }

    private static final Map<String, ReplayEventFormatter> FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> SKILL_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> ITEM_USE_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> ITEM_HIT_FORMATTERS = new HashMap<>();
    /**
     * ITEM_HIT 的“命中类别”格式化器。
     *
     * <p>它和按物品 ID 精确匹配的 {@link #ITEM_HIT_FORMATTERS} 不同：
     * 当前者缺失时，回放层可以根据 hit_type 继续判断这是“刀命中”“枪命中”
     * 还是“球棒命中”，从而让扩展模组自定义武器也能复用同一套句式。</p>
     */
    private static final Map<Identifier, ReplayEventFormatter> ITEM_HIT_TYPE_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> PLATTER_TAKE_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> CONSUME_FORMATTERS = new HashMap<>();
    /**
     * “把某种扩展效果下到托盘里”的专用格式化器。
     *
     * <p>和普通 item_use 不同，这里关心的不是“玩家手里拿了哪个物品”，
     * 而是“托盘最终被挂上了什么效果”。</p>
     *
     * <p>这对扩展试剂、托盘炸弹之类逻辑尤其重要，因为它们真正需要回放表达的是：
     * “某玩家把某效果塞进了托盘”，而不是泛泛地“使用了某物品”。</p>
     */
    private static final Map<Identifier, ReplayEventFormatter> TRAY_EFFECT_PLACEMENT_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> BED_EFFECT_PLACEMENT_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> TRAY_EFFECT_TAKE_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> TRAY_EFFECT_CONSUME_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> SHIELD_SOURCE_FORMATTERS = new HashMap<>();
    private static final Map<Identifier, ReplayEventFormatter> GLOBAL_EVENT_FORMATTERS = new HashMap<>();
    /**
     * 按 death_reason 精确匹配的死亡回放格式化器。
     *
     * <p>默认 death replay 只能表达：
     * “某人死于某死因” 或 “某人被某人击杀”。</p>
     *
     * <p>但扩展模组有时会需要更复杂的句式，例如：
     * “某人因 A 在撕掉 B 的胶带时痛苦而亡”。</p>
     *
     * <p>这类事件虽然本质上仍是 DEATH，
     * 但仅靠默认模板已无法完整表达，因此这里提供一层
     * “按 death_reason 单独接管格式化”的扩展口。</p>
     */
    private static final Map<Identifier, ReplayEventFormatter> DEATH_REASON_FORMATTERS = new HashMap<>();

    public static void registerFormatter(String eventType, ReplayEventFormatter formatter) {
        FORMATTERS.put(eventType, formatter);
    }

    public static void registerSkillFormatter(Identifier skillId, ReplayEventFormatter formatter) {
        SKILL_FORMATTERS.put(skillId, formatter);
    }

    public static void registerItemUseFormatter(Identifier itemId, ReplayEventFormatter formatter) {
        ITEM_USE_FORMATTERS.put(itemId, formatter);
    }

    public static void registerItemHitFormatter(Identifier itemId, ReplayEventFormatter formatter) {
        ITEM_HIT_FORMATTERS.put(itemId, formatter);
    }

    public static void registerItemHitTypeFormatter(Identifier hitType, ReplayEventFormatter formatter) {
        ITEM_HIT_TYPE_FORMATTERS.put(hitType, formatter);
    }

    public static void registerPlatterTakeFormatter(Identifier itemId, ReplayEventFormatter formatter) {
        PLATTER_TAKE_FORMATTERS.put(itemId, formatter);
    }

    public static void registerConsumeFormatter(Identifier itemId, ReplayEventFormatter formatter) {
        CONSUME_FORMATTERS.put(itemId, formatter);
    }

    public static void registerTrayEffectPlacementFormatter(Identifier effectId, ReplayEventFormatter formatter) {
        TRAY_EFFECT_PLACEMENT_FORMATTERS.put(effectId, formatter);
    }

    public static void registerBedEffectPlacementFormatter(Identifier effectId, ReplayEventFormatter formatter) {
        BED_EFFECT_PLACEMENT_FORMATTERS.put(effectId, formatter);
    }

    public static void registerTrayEffectTakeFormatter(Identifier effectId, ReplayEventFormatter formatter) {
        TRAY_EFFECT_TAKE_FORMATTERS.put(effectId, formatter);
    }

    public static void registerTrayEffectConsumeFormatter(Identifier effectId, ReplayEventFormatter formatter) {
        TRAY_EFFECT_CONSUME_FORMATTERS.put(effectId, formatter);
    }

    public static void registerShieldSourceFormatter(Identifier sourceId, ReplayEventFormatter formatter) {
        SHIELD_SOURCE_FORMATTERS.put(sourceId, formatter);
    }

    public static void registerGlobalEventFormatter(Identifier eventId, ReplayEventFormatter formatter) {
        GLOBAL_EVENT_FORMATTERS.put(eventId, formatter);
    }

    public static void registerDeathReasonFormatter(Identifier deathReasonId, ReplayEventFormatter formatter) {
        DEATH_REASON_FORMATTERS.put(deathReasonId, formatter);
    }

    @Nullable
    public static ReplayEventFormatter getFormatter(String eventType) {
        return FORMATTERS.get(eventType);
    }

    @Nullable
    public static ReplayEventFormatter getSkillFormatter(Identifier skillId) {
        return SKILL_FORMATTERS.get(skillId);
    }

    @Nullable
    public static ReplayEventFormatter getItemUseFormatter(Identifier itemId) {
        return ITEM_USE_FORMATTERS.get(itemId);
    }

    @Nullable
    public static ReplayEventFormatter getItemHitFormatter(Identifier itemId) {
        return ITEM_HIT_FORMATTERS.get(itemId);
    }

    @Nullable
    public static ReplayEventFormatter getItemHitTypeFormatter(Identifier hitType) {
        return ITEM_HIT_TYPE_FORMATTERS.get(hitType);
    }

    @Nullable
    public static ReplayEventFormatter getPlatterTakeFormatter(Identifier itemId) {
        return PLATTER_TAKE_FORMATTERS.get(itemId);
    }

    @Nullable
    public static ReplayEventFormatter getConsumeFormatter(Identifier itemId) {
        return CONSUME_FORMATTERS.get(itemId);
    }

    @Nullable
    public static ReplayEventFormatter getTrayEffectPlacementFormatter(Identifier effectId) {
        return TRAY_EFFECT_PLACEMENT_FORMATTERS.get(effectId);
    }

    @Nullable
    public static ReplayEventFormatter getBedEffectPlacementFormatter(Identifier effectId) {
        return BED_EFFECT_PLACEMENT_FORMATTERS.get(effectId);
    }

    @Nullable
    public static ReplayEventFormatter getTrayEffectTakeFormatter(Identifier effectId) {
        return TRAY_EFFECT_TAKE_FORMATTERS.get(effectId);
    }

    @Nullable
    public static ReplayEventFormatter getTrayEffectConsumeFormatter(Identifier effectId) {
        return TRAY_EFFECT_CONSUME_FORMATTERS.get(effectId);
    }

    @Nullable
    public static ReplayEventFormatter getShieldSourceFormatter(Identifier sourceId) {
        return SHIELD_SOURCE_FORMATTERS.get(sourceId);
    }

    @Nullable
    public static ReplayEventFormatter getGlobalEventFormatter(Identifier eventId) {
        return GLOBAL_EVENT_FORMATTERS.get(eventId);
    }

    @Nullable
    public static ReplayEventFormatter getDeathReasonFormatter(Identifier deathReasonId) {
        return DEATH_REASON_FORMATTERS.get(deathReasonId);
    }
}
