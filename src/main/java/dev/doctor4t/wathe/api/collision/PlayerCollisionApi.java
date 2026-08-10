package dev.doctor4t.wathe.api.collision;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.collision.PlayerCollisionShapeHelper;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家之间物理碰撞的公开 API。
 *
 * <p>Wathe 本体默认会在对局中让存活玩家互相成为实体墙。扩展职业如果需要“原版推挤可穿过”
 * 或“完全无碰撞无推挤”，请在这里注册规则，而不是继续 mixin {@code Entity#collidesWith}、
 * {@code EntityView#getEntityCollisions} 或推挤方法。这样服务端判定和客户端移动预测能共用同一套结果，
 * 避免玩家强行穿过时出现反复拉回的体验。</p>
 */
public final class PlayerCollisionApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());

    private static final List<Entry> RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private PlayerCollisionApi() {
    }

    /**
     * 注册一条玩家碰撞规则。
     *
     * <p>priority 越大越先执行；同 priority 下后注册的规则先执行。
     * 返回 {@link PlayerCollisionMode#PASS} 表示继续询问后续规则和 Wathe 默认规则。</p>
     */
    public static void registerRule(@NotNull Identifier id, int priority, @NotNull Rule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (RULES) {
            RULES.removeIf(entry -> entry.id().equals(id));
            RULES.add(new Entry(id, priority, nextOrder++, rule));
            RULES.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 解析 {@code self -> other} 这一方向的最终碰撞模式。
     */
    public static @NotNull PlayerCollisionMode resolve(@NotNull PlayerEntity self, @NotNull PlayerEntity other) {
        if (self.getWorld() != other.getWorld()) {
            return PlayerCollisionMode.PASS;
        }

        GameWorldComponent game = GameWorldComponent.KEY.get(self.getWorld());
        PlayerCollisionContext context = new PlayerCollisionContext(self, other, self.getWorld(), game);
        for (Entry entry : snapshot()) {
            PlayerCollisionMode mode = entry.rule().resolve(context);
            if (mode != null && mode != PlayerCollisionMode.PASS) {
                return mode;
            }
        }

        return resolveWatheDefault(context);
    }

    /**
     * 移动碰撞 shape 是否应该把 {@code other} 当成阻挡 {@code self} 的实体墙。
     */
    public static boolean blocksMovement(@NotNull PlayerEntity self, @NotNull PlayerEntity other) {
        return resolve(self, other).blocksMovement();
    }

    /**
     * 判断玩家之间的原版轻微推挤是否应该被取消。
     *
     * <p>原版推挤一次会同时改动双方速度，因此只要任意方向声明 {@link PlayerCollisionMode#NO_COLLISION}，
     * 就取消这次推挤，保证“完全无碰撞无推挤”的玩家不会被另一侧的原版逻辑挤走。
     *
     * <p>{@link PlayerCollisionMode#SOLID} 虽然保留原版轻微推挤用于重叠解卡，但这份推挤只应该在两个
     * AABB 已经相交时发生。正常贴着玩家实体墙前进时如果继续放行推挤，会让“实体墙裁剪”和“玩家速度互推”
     * 同时作用，客户端体感容易变成先挤进去再被服务端校正回来。</p>
     */
    public static boolean suppressesPush(@NotNull PlayerEntity self, @NotNull PlayerEntity other) {
        PlayerCollisionMode selfToOther = resolve(self, other);
        PlayerCollisionMode otherToSelf = resolve(other, self);
        if (!selfToOther.allowsVanillaPush() || !otherToSelf.allowsVanillaPush()) {
            return true;
        }

        /*
         * SOLID 的“轻微推挤”只作为已经明显重叠后的解卡手段。
         * TP 或服务端强制移动后的几个 tick 内，客户端远端玩家会插值，服务端也可能残留微小推挤速度；
         * 如果只用 Box#intersects，只要擦到一点浮点重叠就会放行原版推挤，移动裁剪和推挤速度会反复抢位置。
         * 这里统一用 Wathe 的 SOLID 碰撞箱判断“是否真的嵌入”，正常贴墙/擦角都取消推挤，让它更像方块阻挡。
         */
        if ((selfToOther.blocksMovement() || otherToSelf.blocksMovement())
                && !PlayerCollisionShapeHelper.hasMeaningfulPushOverlap(self, other)) {
            return true;
        }

        return false;
    }

    private static @NotNull PlayerCollisionMode resolveWatheDefault(@NotNull PlayerCollisionContext context) {
        GameWorldComponent game = context.game();
        if (!game.isRunning() || !game.isAlivePlayerCollisionEnabled()) {
            return PlayerCollisionMode.PASS;
        }
        if (!GameFunctions.isPlayerAliveAndSurvival(context.self())
                || !GameFunctions.isPlayerAliveAndSurvival(context.other())) {
            return PlayerCollisionMode.PASS;
        }

        /*
         * 开局免碰撞窗口只取消 Wathe 额外添加的实体墙，保留原版玩家轻微推挤。
         * 这样倒计时结束前后都不会突然把所有玩家变成完全没有物理反馈的空气。
         */
        if (game.isAlivePlayerCollisionStartDelayActive()) {
            return PlayerCollisionMode.VANILLA_PUSH;
        }

        return PlayerCollisionMode.SOLID;
    }

    private static List<Entry> snapshot() {
        synchronized (RULES) {
            return List.copyOf(RULES);
        }
    }

    @FunctionalInterface
    public interface Rule {
        @NotNull PlayerCollisionMode resolve(@NotNull PlayerCollisionContext context);
    }

    private record Entry(@NotNull Identifier id, int priority, long order, @NotNull Rule rule) {
    }
}
