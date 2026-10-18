package dev.doctor4t.wathe.api.mood;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家“当前有效心情类型”的公开解析 API。
 *
 * <p>{@link Role#getMoodType()} 仍然表示职业注册时的基础类型；扩展职业、词条或临时状态如果需要把
 * REAL 转成 FAKE、FAKE 转成 REAL，或者临时切到 NONE，应在这里注册 resolver，而不是修改全局 Role 对象。
 * Role 是所有同职业玩家共用的注册对象，直接改字段会把其它玩家一起污染，也无法安全同步客户端。</p>
 *
 * <p>Wathe 的服务端心情下降、任务奖励、多任务阈值、精神崩溃、任务发放资格与客户端 HUD
 * 都统一读取本 API 的最终结果，保证“玩法类型”和“显示类型”不会分裂。</p>
 */
public final class PlayerMoodTypeApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<ResolverEntry> RESOLVER_COMPARATOR =
            Comparator.comparingInt(ResolverEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ResolverEntry::order).reversed());
    private static final List<ResolverEntry> RESOLVERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private PlayerMoodTypeApi() {
    }

    /**
     * 注册动态心情类型 resolver。
     *
     * <p>priority 越大越先执行；同 priority 下后注册者先执行。返回 PASS 时继续询问后续规则，
     * 第一条返回 OVERRIDE 的规则成为最终结果。相同 id 重复注册会替换旧规则。</p>
     *
     * <p>resolver 会同时在逻辑服务端和客户端 HUD 中调用，因此动态条件必须来自双方都能读取到的同步状态。
     * 例如 Harpy 的 {@code WorldModifierComponent} 已同步词条表，可以安全用于词条转换；只存在服务端内存里的
     * 临时 Map 则不能直接作为客户端判断来源。</p>
     *
     * <p>resolver 是纯类型选择器，不应改组件、发任务或同步网络；也不能递归调用本 API、
     * {@code moodComponent.getMood()} 或 {@code isLowerThanMid()} 等会再次解析类型的方法。
     * 应根据上下文的基础类型、职业和扩展已同步状态返回结果。</p>
     */
    public static void registerResolver(
            @NotNull Identifier id,
            int priority,
            @NotNull MoodTypeResolver resolver
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resolver, "resolver");
        synchronized (RESOLVERS) {
            RESOLVERS.removeIf(entry -> entry.id().equals(id));
            RESOLVERS.add(new ResolverEntry(id, priority, nextOrder++, resolver));
            RESOLVERS.sort(RESOLVER_COMPARATOR);
        }
    }

    public static @NotNull Role.MoodType resolveMoodType(@NotNull PlayerEntity player) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        return resolveMoodType(player, gameWorld.getRole(player), PlayerMoodComponent.KEY.get(player));
    }

    public static @NotNull Role.MoodType resolveMoodType(
            @NotNull PlayerEntity player,
            @Nullable Role role
    ) {
        return resolveMoodType(player, role, PlayerMoodComponent.KEY.get(player));
    }

    /**
     * 内部组件和公开调用共用的完整解析入口。
     *
     * <p>没有职业时直接返回 NONE，不执行扩展规则，保证未参与本局的玩家不会被过宽规则开启心情系统。
     * 已经分配职业但基础类型为 NONE 的玩家仍可由 resolver 显式转换成 REAL 或 FAKE。</p>
     */
    public static @NotNull Role.MoodType resolveMoodType(
            @NotNull PlayerEntity player,
            @Nullable Role role,
            @NotNull PlayerMoodComponent moodComponent
    ) {
        if (role == null) {
            return Role.MoodType.NONE;
        }
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        Role.MoodType baseMoodType = role.getMoodType();
        MoodTypeContext context = new MoodTypeContext(player, gameWorld, role, moodComponent, baseMoodType);
        for (ResolverEntry entry : resolverSnapshot()) {
            MoodTypeResult result = entry.resolver().resolve(context);
            if (result != null && result.action() == MoodTypeResult.Action.OVERRIDE && result.moodType() != null) {
                return result.moodType();
            }
        }
        return baseMoodType;
    }

    public static boolean hasRealMood(@NotNull PlayerEntity player) {
        return resolveMoodType(player) == Role.MoodType.REAL;
    }

    public static boolean hasFakeMood(@NotNull PlayerEntity player) {
        return resolveMoodType(player) == Role.MoodType.FAKE;
    }

    private static @NotNull List<ResolverEntry> resolverSnapshot() {
        synchronized (RESOLVERS) {
            return List.copyOf(RESOLVERS);
        }
    }

    @FunctionalInterface
    public interface MoodTypeResolver {
        @Nullable MoodTypeResult resolve(@NotNull MoodTypeContext context);
    }

    public record MoodTypeContext(
            @NotNull PlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @NotNull PlayerMoodComponent moodComponent,
            @NotNull Role.MoodType baseMoodType
    ) {
    }

    public record MoodTypeResult(@NotNull Action action, @Nullable Role.MoodType moodType) {
        private static final MoodTypeResult PASS = new MoodTypeResult(Action.PASS, null);

        public static @NotNull MoodTypeResult pass() {
            return PASS;
        }

        public static @NotNull MoodTypeResult override(@NotNull Role.MoodType moodType) {
            return new MoodTypeResult(Action.OVERRIDE, Objects.requireNonNull(moodType, "moodType"));
        }

        public enum Action {
            PASS,
            OVERRIDE
        }
    }

    private record ResolverEntry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull MoodTypeResolver resolver
    ) {
    }
}
