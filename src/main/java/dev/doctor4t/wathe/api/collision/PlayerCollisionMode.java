package dev.doctor4t.wathe.api.collision;

/**
 * Wathe 玩家之间的物理碰撞模式。
 *
 * <p>这里把“能不能作为移动碰撞体”和“是否保留原版轻微推挤”拆成三种常用语义，
 * 方便扩展职业按需要接入，而不是继续 mixin Entity / EntityView 的底层流程。</p>
 */
public enum PlayerCollisionMode {
    /**
     * 不发表意见，继续交给低优先级规则或原版逻辑。
     */
    PASS(false, true),
    /**
     * 像 spark 版本一样把目标玩家当作实体墙，真正阻挡移动。
     *
     * <p>这里仍保留原版玩家轻微推挤的语义，但 Wathe 只会在两个玩家已经重叠时放行这份推挤，
     * 用来处理开局免碰撞结束后两个玩家已经重叠的解卡场景。
     * 如果需要完全没有推挤，请返回 {@link #NO_COLLISION}。</p>
     */
    SOLID(true, true),
    /**
     * 恢复原版玩家手感：没有实体墙式阻挡，但保留原版玩家之间的轻微推挤。
     */
    VANILLA_PUSH(false, true),
    /**
     * 完全无碰撞、无推挤，玩家之间像空气一样穿过。
     */
    NO_COLLISION(false, false);

    private final boolean blocksMovement;
    private final boolean allowsVanillaPush;

    PlayerCollisionMode(boolean blocksMovement, boolean allowsVanillaPush) {
        this.blocksMovement = blocksMovement;
        this.allowsVanillaPush = allowsVanillaPush;
    }

    public boolean blocksMovement() {
        return blocksMovement;
    }

    public boolean allowsVanillaPush() {
        return allowsVanillaPush;
    }
}
