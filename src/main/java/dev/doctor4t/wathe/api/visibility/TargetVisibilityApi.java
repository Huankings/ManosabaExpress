package dev.doctor4t.wathe.api.visibility;

import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家与玩家尸体的“可见、可选中、可交互、可攻击”公开判定入口。
 *
 * <p>这个 API 只负责回答“某个观察者 viewer 面前的某个玩家 / 尸体，此刻是否应该参与某类操作”。
 * 它不会改玩家真实身份、尸体 owner、尸体外观 UUID 或死亡回放数据。扩展职业要做皮肤伪装时仍然应该使用
 * PlayerAppearanceApi / BodyAppearanceApi；要做隐藏、准心不可选中、道具不可交互或武器不可攻击时再接入这里。</p>
 *
 * <p>客户端渲染和准心过滤只是用户体验层，不能作为玩法权威。凡是会产生真实结算的服务端物品、
 * C2S 包或职业能力，都需要在服务端再次调用 {@link #canInteractWithBody(PlayerEntity, PlayerBodyEntity)}
 * 或 {@link #canAttackPlayer(PlayerEntity, PlayerEntity)} 这类方法。</p>
 */
public final class TargetVisibilityApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry<?>> ENTRY_COMPARATOR =
            Comparator.comparingInt((Entry<?> entry) -> entry.priority())
                    .reversed()
                    .thenComparing(Comparator.comparingLong((Entry<?> entry) -> entry.order()).reversed());

    private static final List<Entry<BodyRule>> BODY_RULES = new ArrayList<>();
    private static final List<Entry<PlayerRule>> PLAYER_RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private TargetVisibilityApi() {
    }

    /**
     * 注册玩家尸体规则。
     *
     * <p>priority 越大越先执行；同 priority 下后注册的规则先执行。
     * 返回 {@link Decision#PASS} 表示继续询问低优先级规则，返回 ALLOW / DENY 会立即结束本次判定。</p>
     */
    public static void registerBodyRule(@NotNull Identifier id, int priority, @NotNull BodyRule rule) {
        register(BODY_RULES, id, priority, rule);
    }

    /**
     * 注册玩家实体规则。
     *
     * <p>当前 Wathe 只提供基础接入，不默认改变任何玩家显示或交互行为。
     * 后续扩展职业需要隐藏某些玩家时，在对应职业自己的 handler 里注册规则即可。</p>
     */
    public static void registerPlayerRule(@NotNull Identifier id, int priority, @NotNull PlayerRule rule) {
        register(PLAYER_RULES, id, priority, rule);
    }

    private static <T> void register(@NotNull List<Entry<T>> entries,
                                     @NotNull Identifier id,
                                     int priority,
                                     @NotNull T rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (entries) {
            entries.removeIf(entry -> entry.id().equals(id));
            entries.add(new Entry<>(id, priority, nextOrder++, rule));
            entries.sort((Comparator<? super Entry<T>>) (Comparator<?>) ENTRY_COMPARATOR);
        }
    }

    public static boolean canRenderBody(@Nullable PlayerEntity viewer, @NotNull PlayerBodyEntity body) {
        return canUseBody(viewer, body, Action.RENDER);
    }

    public static boolean canTargetBody(@Nullable PlayerEntity viewer, @NotNull PlayerBodyEntity body) {
        return canUseBody(viewer, body, Action.TARGET);
    }

    public static boolean canInteractWithBody(@Nullable PlayerEntity viewer, @NotNull PlayerBodyEntity body) {
        return canUseBody(viewer, body, Action.INTERACT);
    }

    public static boolean canAttackBody(@Nullable PlayerEntity viewer, @NotNull PlayerBodyEntity body) {
        return canUseBody(viewer, body, Action.ATTACK);
    }

    public static boolean canRenderPlayer(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target) {
        return canUsePlayer(viewer, target, Action.RENDER);
    }

    public static boolean canTargetPlayer(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target) {
        return canUsePlayer(viewer, target, Action.TARGET);
    }

    public static boolean canInteractWithPlayer(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target) {
        return canUsePlayer(viewer, target, Action.INTERACT);
    }

    public static boolean canAttackPlayer(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target) {
        return canUsePlayer(viewer, target, Action.ATTACK);
    }

    public static boolean canRenderEntity(@Nullable PlayerEntity viewer, @NotNull Entity entity) {
        if (entity instanceof PlayerBodyEntity body) {
            return canRenderBody(viewer, body);
        }
        if (entity instanceof PlayerEntity player) {
            return canRenderPlayer(viewer, player);
        }
        return true;
    }

    public static boolean canTargetEntity(@Nullable PlayerEntity viewer, @NotNull Entity entity) {
        if (entity instanceof PlayerBodyEntity body) {
            return canTargetBody(viewer, body);
        }
        if (entity instanceof PlayerEntity player) {
            return canTargetPlayer(viewer, player);
        }
        return true;
    }

    public static boolean canInteractWithEntity(@Nullable PlayerEntity viewer, @NotNull Entity entity) {
        if (entity instanceof PlayerBodyEntity body) {
            return canInteractWithBody(viewer, body);
        }
        if (entity instanceof PlayerEntity player) {
            return canInteractWithPlayer(viewer, player);
        }
        return true;
    }

    public static boolean canAttackEntity(@Nullable PlayerEntity viewer, @NotNull Entity entity) {
        if (entity instanceof PlayerBodyEntity body) {
            return canAttackBody(viewer, body);
        }
        if (entity instanceof PlayerEntity player) {
            return canAttackPlayer(viewer, player);
        }
        return true;
    }

    private static boolean canUseBody(@Nullable PlayerEntity viewer, @NotNull PlayerBodyEntity body, @NotNull Action action) {
        if (viewer == null) {
            /*
             * 客户端刚进世界、还没有本地玩家时也可能提前渲染实体。
             * 这种阶段没有可靠观察者身份，默认保持可见，避免把普通尸体误隐藏。
             */
            return true;
        }

        BodyContext context = new BodyContext(viewer, body, action);
        for (Entry<BodyRule> entry : snapshot(BODY_RULES)) {
            Decision decision = entry.rule().resolve(context);
            if (decision == Decision.DENY) {
                return false;
            }
            if (decision == Decision.ALLOW) {
                return true;
            }
        }
        return true;
    }

    private static boolean canUsePlayer(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target, @NotNull Action action) {
        if (viewer == null) {
            return true;
        }

        PlayerContext context = new PlayerContext(viewer, target, action);
        for (Entry<PlayerRule> entry : snapshot(PLAYER_RULES)) {
            Decision decision = entry.rule().resolve(context);
            if (decision == Decision.DENY) {
                return false;
            }
            if (decision == Decision.ALLOW) {
                return true;
            }
        }
        return true;
    }

    private static <T> List<Entry<T>> snapshot(@NotNull List<Entry<T>> entries) {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    @FunctionalInterface
    public interface BodyRule {
        @NotNull Decision resolve(@NotNull BodyContext context);
    }

    @FunctionalInterface
    public interface PlayerRule {
        @NotNull Decision resolve(@NotNull PlayerContext context);
    }

    public record BodyContext(@NotNull PlayerEntity viewer,
                              @NotNull PlayerBodyEntity body,
                              @NotNull Action action) {
    }

    public record PlayerContext(@NotNull PlayerEntity viewer,
                                @NotNull PlayerEntity target,
                                @NotNull Action action) {
    }

    public enum Action {
        /**
         * 渲染实体本体或轮廓。
         */
        RENDER,
        /**
         * 准心 / 射线是否能把实体当作目标。
         */
        TARGET,
        /**
         * 右键、物品、职业能力这类非攻击性交互。
         */
        INTERACT,
        /**
         * 匕首、枪、疯魔近战等会造成命中 / 击杀结算的攻击。
         */
        ATTACK
    }

    public enum Decision {
        PASS,
        ALLOW,
        DENY
    }

    private record Entry<T>(@NotNull Identifier id, int priority, long order, @NotNull T rule) {
    }
}
