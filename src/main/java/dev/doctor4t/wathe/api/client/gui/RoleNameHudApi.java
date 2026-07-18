package dev.doctor4t.wathe.api.client.gui;

import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 准心名字 HUD 的公开客户端接入点。
 *
 * <p>这里负责两类扩展：
 * 1. 改写 Wathe 自己的准心名字行为，比如显示伪装目标名、替换射线相机、隐藏同伙提示；
 * 2. 在准心 HUD 周围补额外职业提示，替代扩展模组继续 mixin RoleNameRenderer。</p>
 */
@Environment(EnvType.CLIENT)
public final class RoleNameHudApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry<?>> ENTRY_COMPARATOR =
            Comparator.comparingInt((Entry<?> entry) -> entry.priority())
                    .reversed()
                    .thenComparing(Comparator.comparingLong((Entry<?> entry) -> entry.order()).reversed());

    private static final List<Entry<HudVisibilityHandler>> HUD_VISIBILITY_HANDLERS = new ArrayList<>();
    private static final List<Entry<RaycastSourceHandler>> RAYCAST_SOURCE_HANDLERS = new ArrayList<>();
    private static final List<Entry<PlayerTargetFilter>> PLAYER_TARGET_FILTERS = new ArrayList<>();
    private static final List<Entry<NameHandler>> NAME_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortStateHandler>> COHORT_STATE_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortTargetStateHandler>> COHORT_TARGET_STATE_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortHintHandler>> COHORT_HINT_HANDLERS = new ArrayList<>();
    private static final List<Entry<ExtraHudRenderer>> EXTRA_HUD_RENDERERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private RoleNameHudApi() {
    }

    public static void registerHudVisibility(@NotNull Identifier id, int priority, @NotNull HudVisibilityHandler handler) {
        register(HUD_VISIBILITY_HANDLERS, id, priority, handler);
    }

    public static void registerRaycastSource(@NotNull Identifier id, int priority, @NotNull RaycastSourceHandler handler) {
        register(RAYCAST_SOURCE_HANDLERS, id, priority, handler);
    }

    public static void registerPlayerTargetFilter(@NotNull Identifier id, int priority, @NotNull PlayerTargetFilter handler) {
        register(PLAYER_TARGET_FILTERS, id, priority, handler);
    }

    public static void registerName(@NotNull Identifier id, int priority, @NotNull NameHandler handler) {
        register(NAME_HANDLERS, id, priority, handler);
    }

    public static void registerCohortState(@NotNull Identifier id, int priority, @NotNull CohortStateHandler handler) {
        register(COHORT_STATE_HANDLERS, id, priority, handler);
    }

    /**
     * 注册“目标单向显示为杀手同伙”的规则。
     *
     * <p>这个接口只回答“viewer 的准心指向 target 时，target 是否应该显示成杀手同伙”；
     * 它不会让 viewer 自己获得查看同伙提示的资格。最终 HUD 仍然会用
     * {@link #countsAsCohort(ClientPlayerEntity, PlayerEntity, boolean)} 判断 viewer 自己是不是
     * 真正的双向同伙成员。Mimic、Jester、Vulture、Dreamer 这类“杀手看他们像同伙，
     * 但他们本人不能反查杀手”的职业应该接入这里，而不是接入 {@link #registerCohortState}。</p>
     */
    public static void registerCohortTargetState(@NotNull Identifier id, int priority, @NotNull CohortTargetStateHandler handler) {
        register(COHORT_TARGET_STATE_HANDLERS, id, priority, handler);
    }

    public static void registerCohortHint(@NotNull Identifier id, int priority, @NotNull CohortHintHandler handler) {
        register(COHORT_HINT_HANDLERS, id, priority, handler);
    }

    public static void registerExtraHud(@NotNull Identifier id, int priority, @NotNull ExtraHudRenderer renderer) {
        register(EXTRA_HUD_RENDERERS, id, priority, renderer);
    }

    private static <T> void register(List<Entry<T>> entries, Identifier id, int priority, T handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (entries) {
            entries.removeIf(entry -> entry.id().equals(id));
            entries.add(new Entry<>(id, priority, nextOrder++, handler));
            entries.sort((Comparator<? super Entry<T>>) (Comparator<?>) ENTRY_COMPARATOR);
        }
    }

    public static boolean shouldRenderHud(@NotNull ClientPlayerEntity player) {
        for (Entry<HudVisibilityHandler> entry : snapshot(HUD_VISIBILITY_HANDLERS)) {
            VisibilityResult result = entry.handler().shouldRender(player);
            if (result == VisibilityResult.HIDE) {
                return false;
            }
            if (result == VisibilityResult.SHOW) {
                return true;
            }
        }
        return true;
    }

    public static @NotNull Entity resolveRaycastSource(@NotNull ClientPlayerEntity player) {
        for (Entry<RaycastSourceHandler> entry : snapshot(RAYCAST_SOURCE_HANDLERS)) {
            Entity source = entry.handler().getRaycastSource(player);
            if (source != null) {
                return source;
            }
        }
        return player;
    }

    public static boolean shouldIncludePlayerTarget(@NotNull ClientPlayerEntity viewer, @NotNull PlayerEntity target) {
        for (Entry<PlayerTargetFilter> entry : snapshot(PLAYER_TARGET_FILTERS)) {
            TargetResult result = entry.handler().shouldInclude(viewer, target);
            if (result == TargetResult.DENY) {
                return false;
            }
            if (result == TargetResult.ALLOW) {
                return true;
            }
        }
        return true;
    }

    public static @NotNull Text resolveName(@NotNull ClientPlayerEntity viewer,
                                            @NotNull PlayerEntity target,
                                            @NotNull Text originalName) {
        for (Entry<NameHandler> entry : snapshot(NAME_HANDLERS)) {
            Text result = entry.handler().getName(viewer, target, originalName);
            if (result != null) {
                return result;
            }
        }
        return originalName;
    }

    public static boolean countsAsCohort(@NotNull ClientPlayerEntity viewer,
                                         @NotNull PlayerEntity subject,
                                         boolean vanillaValue) {
        for (Entry<CohortStateHandler> entry : snapshot(COHORT_STATE_HANDLERS)) {
            Boolean result = entry.handler().countsAsCohort(viewer, subject, vanillaValue);
            if (result != null) {
                return result;
            }
        }
        return vanillaValue;
    }

    public static boolean showsAsCohortTarget(@NotNull ClientPlayerEntity viewer,
                                              @NotNull PlayerEntity target,
                                              boolean vanillaValue) {
        for (Entry<CohortTargetStateHandler> entry : snapshot(COHORT_TARGET_STATE_HANDLERS)) {
            Boolean result = entry.handler().showsAsCohortTarget(viewer, target, vanillaValue);
            if (result != null) {
                return result;
            }
        }
        return vanillaValue;
    }

    public static boolean shouldShowCohortHint(@NotNull ClientPlayerEntity viewer,
                                               @NotNull PlayerEntity target,
                                               boolean vanillaValue) {
        for (Entry<CohortHintHandler> entry : snapshot(COHORT_HINT_HANDLERS)) {
            VisibilityResult result = entry.handler().shouldShow(viewer, target, vanillaValue);
            if (result == VisibilityResult.HIDE) {
                return false;
            }
            if (result == VisibilityResult.SHOW) {
                return true;
            }
        }
        return vanillaValue;
    }

    public static void renderExtraHud(@NotNull Context context) {
        for (Entry<ExtraHudRenderer> entry : snapshot(EXTRA_HUD_RENDERERS)) {
            entry.handler().render(context);
        }
    }

    /**
     * 给扩展 HUD provider 使用的统一尸体射线工具。
     *
     * <p>这样 kinssaba / noellesroles 这类“准心对准尸体时显示额外信息”的逻辑，
     * 不需要再 mixin 到 RoleNameRenderer 的某个局部变量位置。</p>
     */
    public static @Nullable PlayerBodyEntity findLookedAtBody(@NotNull ClientPlayerEntity player, float range) {
        HitResult hitResult = ProjectileUtil.getCollision(player, entity -> entity instanceof PlayerBodyEntity, range);
        if (hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof PlayerBodyEntity body) {
            return body;
        }
        return null;
    }

    public static float defaultLookRange(@NotNull PlayerEntity player) {
        return GameFunctions.isPlayerSpectatingOrCreative(player) ? 8.0F : 2.0F;
    }

    private static <T> List<Entry<T>> snapshot(List<Entry<T>> entries) {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public record Context(@NotNull TextRenderer renderer,
                          @NotNull ClientPlayerEntity player,
                          @NotNull DrawContext drawContext,
                          @NotNull RenderTickCounter tickCounter,
                          float range,
                          @Nullable PlayerEntity targetPlayer,
                          @Nullable Text displayedTargetName,
                          float nametagAlpha,
                          float noteAlpha) {
    }

    @FunctionalInterface
    public interface HudVisibilityHandler {
        @NotNull VisibilityResult shouldRender(@NotNull ClientPlayerEntity player);
    }

    @FunctionalInterface
    public interface RaycastSourceHandler {
        @Nullable Entity getRaycastSource(@NotNull ClientPlayerEntity player);
    }

    @FunctionalInterface
    public interface PlayerTargetFilter {
        @NotNull TargetResult shouldInclude(@NotNull ClientPlayerEntity viewer, @NotNull PlayerEntity target);
    }

    @FunctionalInterface
    public interface NameHandler {
        @Nullable Text getName(@NotNull ClientPlayerEntity viewer, @NotNull PlayerEntity target, @NotNull Text originalName);
    }

    @FunctionalInterface
    public interface CohortStateHandler {
        @Nullable Boolean countsAsCohort(@NotNull ClientPlayerEntity viewer, @NotNull PlayerEntity subject, boolean vanillaValue);
    }

    @FunctionalInterface
    public interface CohortTargetStateHandler {
        @Nullable Boolean showsAsCohortTarget(@NotNull ClientPlayerEntity viewer,
                                             @NotNull PlayerEntity target,
                                             boolean vanillaValue);
    }

    @FunctionalInterface
    public interface CohortHintHandler {
        @NotNull VisibilityResult shouldShow(@NotNull ClientPlayerEntity viewer,
                                             @NotNull PlayerEntity target,
                                             boolean vanillaValue);
    }

    @FunctionalInterface
    public interface ExtraHudRenderer {
        void render(@NotNull Context context);
    }

    public enum VisibilityResult {
        PASS,
        SHOW,
        HIDE
    }

    public enum TargetResult {
        PASS,
        ALLOW,
        DENY
    }

    private record Entry<T>(@NotNull Identifier id, int priority, long order, @NotNull T handler) {
    }
}
