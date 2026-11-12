package dev.doctor4t.wathe.api.client.gui;

import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
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
    private static final List<Entry<EntityNameHandler>> ENTITY_NAME_HANDLERS = new ArrayList<>();
    private static final List<Entry<TargetLineHandler>> ROLE_DETAILS_LINE_HANDLERS = new ArrayList<>();
    private static final List<Entry<TargetLineHandler>> AFFILIATION_LINE_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortStateHandler>> COHORT_STATE_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortTargetStateHandler>> COHORT_TARGET_STATE_HANDLERS = new ArrayList<>();
    private static final List<Entry<CohortHintHandler>> COHORT_HINT_HANDLERS = new ArrayList<>();
    /*
     * 统一的准心附加行注册表。
     *
     * <p>旧的 ExtraHudRenderer 仍然保留给兼容场景，但它只能自行绘制固定坐标，
     * 无法知道同伙行或其它扩展行已经占用了多少空间。新的附加行 provider 只返回
     * 文字、颜色、缩放和相对位置，最终坐标由 RoleNameRenderer 统一计算。</p>
     */
    private static final List<Entry<AdditionalLineHandler>> ADDITIONAL_LINE_HANDLERS = new ArrayList<>();
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

    /**
     * 注册非玩家实体的准心名牌。
     *
     * <p>这个接口只面向“扩展职业生成了一个需要像玩家一样显示名字的实体”这种窄场景，
     * 例如 NoellesRoles 魔术师播放体。普通玩家名字仍然走 {@link #registerName}，
     * 这样同伙提示、精神错乱混淆和其他玩家专属逻辑不会被非玩家实体误触发。</p>
     */
    public static void registerEntityName(@NotNull Identifier id, int priority, @NotNull EntityNameHandler handler) {
        register(ENTITY_NAME_HANDLERS, id, priority, handler);
    }

    /**
     * 注册准心玩家名上方的“职业详情行”。
     *
     * <p>该行由 Wathe 统一参与布局，位于阵营归属行上方。HarpyModLoader 这类职业加载器
     * 可以在这里显示“职业名 + 词条”，而不必再 mixin {@code RoleNameRenderer} 的局部变量
     * 或某一次 {@code drawTextWithShadow} 调用。返回 {@code null} 表示继续询问低优先级规则。</p>
     */
    public static void registerRoleDetailsLine(@NotNull Identifier id,
                                               int priority,
                                               @NotNull TargetLineHandler handler) {
        register(ROLE_DETAILS_LINE_HANDLERS, id, priority, handler);
    }

    /**
     * 注册准心玩家名上方的“阵营归属行”。
     *
     * <p>provider 返回的是不带中括号的正文与整行颜色；Wathe 最终统一通过
     * {@code hud.wathe.role_name.affiliation_format} 包装成 {@code [%s]}，从而保证中括号和正文
     * 使用完全相同的颜色、淡入透明度和字号。高优先级扩展可覆盖默认四阵营文案。</p>
     */
    public static void registerAffiliationLine(@NotNull Identifier id,
                                               int priority,
                                               @NotNull TargetLineHandler handler) {
        register(AFFILIATION_LINE_HANDLERS, id, priority, handler);
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

    /**
     * 注册准心名字区域的动态附加行。
     *
     * <p>provider 不应该自己操作矩阵坐标。Wathe 会先绘制职业、阵营、名字和杀手同伙，
     * 再按注册优先级绘制 {@link AdditionalLine #BELOW_COHORT} 行；
     * {@link AdditionalLine #ABOVE_NAME} / {@link AdditionalLine #ABOVE_ROLE_DETAILS} 行则会
     * 分别排在玩家名字或全部基础信息上方。这样多个扩展同时提供
     * 信息时，后一行会自动避让前一行，不会因为某个模组增加了提示而互相覆盖。</p>
     */
    public static void registerAdditionalLine(@NotNull Identifier id,
                                               int priority,
                                               @NotNull AdditionalLineHandler handler) {
        register(ADDITIONAL_LINE_HANDLERS, id, priority, handler);
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

    public static @Nullable Text resolveEntityName(@NotNull ClientPlayerEntity viewer,
                                                   @NotNull Entity target) {
        for (Entry<EntityNameHandler> entry : snapshot(ENTITY_NAME_HANDLERS)) {
            Text result = entry.handler().getName(viewer, target);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 解析职业详情行。该方法公开是为了让其它客户端 HUD 在确有需要时复用最终覆盖结果，
     * 但普通扩展只需要注册 provider，不应自行复制 RoleNameRenderer 的布局。
     */
    public static @Nullable RoleNameLine resolveRoleDetailsLine(@NotNull ClientPlayerEntity viewer,
                                                                @NotNull PlayerEntity target,
                                                                @Nullable Role targetRole) {
        TargetLineContext context = new TargetLineContext(viewer, target, targetRole);
        for (Entry<TargetLineHandler> entry : snapshot(ROLE_DETAILS_LINE_HANDLERS)) {
            RoleNameLine result = entry.handler().resolve(context);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 解析阵营归属行。
     *
     * <p>扩展 provider 全部 PASS 后，只有 Wathe 定义的“普通非存活观察者”才会获得默认行；
     * 具有 {@code PlayerLifeStateApi} 特殊存活授权的 spectator/creative 会继续按存活玩家处理，
     * 不会因为原版游戏模式而看到职业阵营信息。</p>
     */
    public static @Nullable RoleNameLine resolveAffiliationLine(@NotNull ClientPlayerEntity viewer,
                                                                @NotNull PlayerEntity target,
                                                                @Nullable Role targetRole) {
        TargetLineContext context = new TargetLineContext(viewer, target, targetRole);
        for (Entry<TargetLineHandler> entry : snapshot(AFFILIATION_LINE_HANDLERS)) {
            RoleNameLine result = entry.handler().resolve(context);
            if (result != null) {
                return result;
            }
        }
        if (!GameFunctions.isPlayerSpectatingOrCreative(viewer) || targetRole == null) {
            return null;
        }
        return defaultAffiliationLine(targetRole.getFaction());
    }

    /**
     * 把 Wathe 基础四阵营转换为公开的准心阵营行。
     * 扩展可以复用此方法取得稳定的翻译 key 和 {@link Faction#displayColor()}，
     * 不需要在自己的客户端代码里再次复制颜色常量。
     */
    public static @NotNull RoleNameLine defaultAffiliationLine(@NotNull Faction faction) {
        String translationKey = switch (faction) {
            case CIVILIAN -> "hud.wathe.role_name.affiliation.civilian";
            case VIGILANTE -> "hud.wathe.role_name.affiliation.vigilante";
            case KILLER -> "hud.wathe.role_name.affiliation.killer";
            case NEUTRAL -> "hud.wathe.role_name.affiliation.neutral";
        };
        return new RoleNameLine(Text.translatable(translationKey), faction.displayColor());
    }

    /**
     * 统一给阵营正文加上中括号。这里保留 Text 参数而不是提前拼成字符串，
     * 让扩展提供的翻译文本仍能正确跟随客户端语言切换。
     */
    public static @NotNull Text formatAffiliationLine(@NotNull RoleNameLine line) {
        return Text.translatable("hud.wathe.role_name.affiliation_format", line.text());
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

    /** 解析同一帧所有需要统一排版的准心附加行。 */
    public static @NotNull List<AdditionalLine> resolveAdditionalLines(@NotNull Context context) {
        List<AdditionalLine> lines = new ArrayList<>();
        for (Entry<AdditionalLineHandler> entry : snapshot(ADDITIONAL_LINE_HANDLERS)) {
            AdditionalLine line = entry.handler().resolve(context);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 给扩展 HUD provider 使用的统一尸体射线工具。
     *
     * <p>这样 kinssaba / noellesroles 这类“准心对准尸体时显示额外信息”的逻辑，
     * 不需要再 mixin 到 RoleNameRenderer 的某个局部变量位置。</p>
     */
    public static @Nullable PlayerBodyEntity findLookedAtBody(@NotNull ClientPlayerEntity player, float range) {
        HitResult hitResult = ProjectileUtil.getCollision(
                player,
                entity -> entity instanceof PlayerBodyEntity body && TargetVisibilityApi.canTargetBody(player, body),
                range
        );
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
                          @Nullable Entity targetEntity,
                          @Nullable Text displayedTargetName,
                          float nametagAlpha,
                          float noteAlpha) {
    }

    /**
     * 职业详情行和阵营归属行共用的只读解析上下文。
     */
    public record TargetLineContext(@NotNull ClientPlayerEntity viewer,
                                    @NotNull PlayerEntity target,
                                    @Nullable Role targetRole) {
    }

    /**
     * 一条由 RoleNameRenderer 统一排版的文字。
     * color 只保存 24 位 RGB；每帧淡入淡出所需的 alpha 由 Wathe 在最终绘制时补入。
     */
    public record RoleNameLine(@NotNull Text text, int color) {
        public RoleNameLine {
            Objects.requireNonNull(text, "text");
            color &= 0x00FFFFFF;
        }
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
    public interface EntityNameHandler {
        @Nullable Text getName(@NotNull ClientPlayerEntity viewer, @NotNull Entity target);
    }

    @FunctionalInterface
    public interface TargetLineHandler {
        @Nullable RoleNameLine resolve(@NotNull TargetLineContext context);
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

    @FunctionalInterface
    public interface AdditionalLineHandler {
        @Nullable AdditionalLine resolve(@NotNull Context context);
    }

    /**
     * 一条由 Wathe 统一放入准心名字布局的附加信息。
     * color 保存 24 位 RGB，透明度由当前 nametagAlpha 统一补入。
     */
    public record AdditionalLine(@NotNull Text text,
                                  int color,
                                  float scale,
                                  @NotNull Placement placement) {
        public AdditionalLine {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(placement, "placement");
            color &= 0x00FFFFFF;
            if (!Float.isFinite(scale) || scale <= 0.0F) {
                scale = 1.0F;
            }
        }

        public static AdditionalLine below(@NotNull Text text, int color) {
            return new AdditionalLine(text, color, 1.0F, Placement.BELOW_COHORT);
        }

        public static AdditionalLine below(@NotNull Text text, int color, float scale) {
            return new AdditionalLine(text, color, scale, Placement.BELOW_COHORT);
        }

        public static AdditionalLine aboveName(@NotNull Text text, int color) {
            return new AdditionalLine(text, color, 1.0F, Placement.ABOVE_NAME);
        }

        public static AdditionalLine aboveName(@NotNull Text text, int color, float scale) {
            return new AdditionalLine(text, color, scale, Placement.ABOVE_NAME);
        }

        public static AdditionalLine aboveRoleDetails(@NotNull Text text, int color) {
            return new AdditionalLine(text, color, 1.0F, Placement.ABOVE_ROLE_DETAILS);
        }

        public static AdditionalLine aboveRoleDetails(@NotNull Text text, int color, float scale) {
            return new AdditionalLine(text, color, scale, Placement.ABOVE_ROLE_DETAILS);
        }
    }

    public enum Placement {
        ABOVE_ROLE_DETAILS,
        ABOVE_NAME,
        BELOW_COHORT
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
