package dev.doctor4t.wathe.api.client.appearance;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 普通非存活观察者的显示身份和尸体透视颜色公开入口。
 *
 * <p>观察者本能的最高优先级负责统一观看资格，这里的独立优先级链负责身份覆盖。
 * 扩展返回 null 表示 PASS，同优先级后注册优先，相同 id 替换旧规则。
 * resolver 只读取同步数据，显示资料来源 UUID 可供 Harpy 查询对应身体的词条。</p>
 */
@Environment(EnvType.CLIENT)
public final class ObserverRoleDisplayApi {
    /** 普通显示覆盖规则的默认优先级。 */
    public static final int DEFAULT_PRIORITY = 0;
    /** 观察者本能入口优先级，先统一观察者视角，再在本 API 内解析扩展身份。 */
    public static final int OBSERVER_INSTINCT_PRIORITY = Integer.MAX_VALUE;

    private static final Comparator<Entry<?>> ORDER = Comparator
            .comparingInt((Entry<?> entry) -> entry.priority()).reversed()
            .thenComparing(Comparator.comparingLong((Entry<?> entry) -> entry.order()).reversed());
    private static final List<Entry<PlayerDisplayHandler>> PLAYER_HANDLERS = new ArrayList<>();
    private static final List<Entry<BodyColorHandler>> BODY_HANDLERS = new ArrayList<>();
    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    private ObserverRoleDisplayApi() {}

    public static void registerPlayerDisplay(@NotNull Identifier id, int priority,
                                             @NotNull PlayerDisplayHandler handler) {
        register(PLAYER_HANDLERS, id, priority, handler);
    }

    public static void registerBodyColor(@NotNull Identifier id, int priority,
                                         @NotNull BodyColorHandler handler) {
        register(BODY_HANDLERS, id, priority, handler);
    }

    /** 存活玩家直接获得调用方传入的默认身份，只有非存活观察者执行覆盖规则。 */
    public static @NotNull PlayerDisplay resolvePlayerDisplay(@NotNull PlayerEntity viewer,
                                                              @NotNull PlayerEntity target,
                                                              @Nullable Role defaultRole) {
        if (GameFunctions.isPlayerSpectatingOrCreative(viewer)) {
            PlayerContext context = new PlayerContext(viewer, target, defaultRole);
            for (Entry<PlayerDisplayHandler> entry : snapshot(PLAYER_HANDLERS)) {
                PlayerDisplay result = entry.handler().resolve(context);
                if (result != null) return result;
            }
        }
        return new PlayerDisplay(defaultRole, target.getUuid());
    }

    /** 默认颜色由原透视路径提供，返回 -1 表示不描边；特殊存活旁观也经过存活边界过滤。 */
    public static int resolveBodyColor(@NotNull PlayerEntity viewer, @NotNull PlayerBodyEntity body,
                                       int defaultColor) {
        if (!GameFunctions.isPlayerSpectatingOrCreative(viewer)) return -1;
        BodyContext context = new BodyContext(viewer, body, defaultColor);
        for (Entry<BodyColorHandler> entry : snapshot(BODY_HANDLERS)) {
            Integer color = entry.handler().resolve(context);
            if (color != null) return color < 0 ? -1 : color & 0x00FFFFFF;
        }
        return defaultColor;
    }

    private static <T> void register(List<Entry<T>> handlers, Identifier id, int priority, T handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (handlers) {
            handlers.removeIf(entry -> entry.id().equals(id));
            handlers.add(new Entry<>(id, priority, NEXT_ORDER.getAndIncrement(), handler));
            handlers.sort(ORDER);
        }
    }

    private static <T> List<Entry<T>> snapshot(List<Entry<T>> handlers) {
        synchronized (handlers) {
            return List.copyOf(handlers);
        }
    }

    /** role 决定职业名、职业色和阵营，detailsSourceUuid 决定身体的词条资料来源。 */
    public record PlayerDisplay(@Nullable Role role, @NotNull UUID detailsSourceUuid) {
        public PlayerDisplay {
            Objects.requireNonNull(detailsSourceUuid, "detailsSourceUuid");
        }
    }

    public record PlayerContext(@NotNull PlayerEntity viewer, @NotNull PlayerEntity target,
                                @Nullable Role defaultRole) {}

    public record BodyContext(@NotNull PlayerEntity viewer, @NotNull PlayerBodyEntity body,
                              int defaultColor) {}

    @FunctionalInterface
    public interface PlayerDisplayHandler {
        @Nullable PlayerDisplay resolve(@NotNull PlayerContext context);
    }

    @FunctionalInterface
    public interface BodyColorHandler {
        @Nullable Integer resolve(@NotNull BodyContext context);
    }

    private record Entry<T>(Identifier id, int priority, long order, T handler) {}
}
