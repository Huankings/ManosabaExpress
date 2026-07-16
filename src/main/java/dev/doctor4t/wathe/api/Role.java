package dev.doctor4t.wathe.api;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public final class Role {
    private final Identifier identifier;
    private final int color;
    private final boolean isInnocent;
    private final boolean canUseKiller;
    private final MoodType moodType;
    private final int maxSprintTime;
    private final boolean canSeeTime;
    /**
     * 角色阵营覆写值。
     *
     * <p>这里不通过新增构造器来传值，而是保留旧构造器签名，
     * 避免依赖 {@code Role.<init>} 的扩展模组 mixin 在运行时因为重载构造器而目标歧义。</p>
     */
    private @Nullable Faction factionOverride;

    public enum MoodType {
        NONE, REAL, FAKE
    }

    /**
     * @param identifier    角色的mod ID和名称
     * @param color         角色职业颜色
     * @param isInnocent    当拥有这个角色的人被射击并且在胜利条件中被视为平民时，枪是否会掉落（是则掉落，不是则不掉落）
     * @param canUseKiller  可以看到并且使用杀手功能
     * @param moodType      角色的心情类型（真或假）
     * @param maxSprintTime 最大冲刺时间（以ticks为单位）
     * @param canSeeTime    角色是否可以看到时间
     */
    public Role(Identifier identifier, int color, boolean isInnocent, boolean canUseKiller, MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        this.identifier = identifier;
        this.color = color;
        this.isInnocent = isInnocent;
        this.canUseKiller = canUseKiller;
        this.moodType = moodType;
        this.maxSprintTime = maxSprintTime;
        this.canSeeTime = canSeeTime;
    }

    public Identifier identifier() {
        return identifier;
    }

    public int color() {
        return color;
    }

    public Faction getFaction() {
        return factionOverride != null ? factionOverride : inferFaction(isInnocent, canUseKiller, identifier);
    }

    public boolean isInnocent() {
        return isInnocent;
    }

    public boolean canUseKiller() {
        return canUseKiller;
    }

    public MoodType getMoodType() {
        return moodType;
    }

    public int getMaxSprintTime() {
        return maxSprintTime;
    }

    public boolean canSeeTime() {
        return canSeeTime;
    }

    /**
     * 由本体注册表在运行期补充更精确的阵营定义。
     *
     * <p>这样旧扩展职业仍能继续使用原来的七参构造，
     * 需要精确区分“中立 / 杀手 / 义警 / 平民阵营色”的新扩展则只需额外调用注册表接口。</p>
     */
    void setFactionOverride(@Nullable Faction faction) {
        this.factionOverride = faction;
    }

    private static Faction inferFaction(boolean isInnocent, boolean canUseKiller, Identifier identifier) {
        if (canUseKiller) {
            return Faction.KILLER;
        }
        if (!isInnocent) {
            return Faction.NEUTRAL;
        }
        return Faction.CIVILIAN;
    }
}
