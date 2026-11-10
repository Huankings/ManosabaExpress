package dev.doctor4t.wathe.api.client.gui;

import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 玩家尸体准心信息公开 API。
 *
 * <p>固定五行的可见性、内容覆盖、凶手状态覆盖和附加提示都走同一条优先级链。
 * 扩展只返回文本/决策，不接触矩阵、射线和坐标，从根源上避免多个职业 HUD 相互顶行。</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerBodyHudApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry<?>> ENTRY_COMPARATOR =
            Comparator.comparingInt((Entry<?> entry) -> entry.priority())
                    .reversed()
                    .thenComparing(Comparator.comparingLong((Entry<?> entry) -> entry.order()).reversed());
    private static final List<Entry<LineRule>> VISIBILITY_RULES = new ArrayList<>();
    private static final List<Entry<LineProvider>> LINE_PROVIDERS = new ArrayList<>();
    private static final List<Entry<AdditionalLineProvider>> ADDITIONAL_LINE_PROVIDERS = new ArrayList<>();
    private static final List<Entry<KillerStatusProvider>> KILLER_STATUS_PROVIDERS = new ArrayList<>();
    private static long nextOrder;

    private PlayerBodyHudApi() {
    }

    public static void registerLineRule(@NotNull Identifier id, @NotNull BodyHudLine line, int priority, @NotNull LineRule rule) {
        register(VISIBILITY_RULES, id, priority,
                (context, candidate) -> candidate == line ? rule.resolve(context, candidate) : BodyHudDecision.PASS);
    }

    public static void registerLineProvider(@NotNull Identifier id, @NotNull BodyHudLine line, int priority, @NotNull LineProvider provider) {
        register(LINE_PROVIDERS, id, priority,
                (context, candidate) -> candidate == line ? provider.resolve(context, candidate) : null);
    }

    public static void registerAdditionalLines(@NotNull Identifier id, int priority, @NotNull AdditionalLineProvider provider) {
        register(ADDITIONAL_LINE_PROVIDERS, id, priority, provider);
    }

    public static void registerKillerStatusProvider(@NotNull Identifier id, int priority, @NotNull KillerStatusProvider provider) {
        register(KILLER_STATUS_PROVIDERS, id, priority, provider);
    }

    public static @NotNull BodyHudDecision resolveVisibility(@NotNull Context context, @NotNull BodyHudLine line) {
        for (Entry<LineRule> entry : snapshot(VISIBILITY_RULES)) {
            BodyHudDecision decision = entry.handler().resolve(context, line);
            if (decision != BodyHudDecision.PASS) {
                return decision;
            }
        }

        /*
         * 玩家名字现在只允许非存活观察者看到。这样尸体皮肤/外观伪装不会被活人
         * 通过准心名字直接反推真实死者；医师、秃鹫和验尸官仍可由扩展规则开放其它行。
         */
        if (line == BodyHudLine.NAME) {
            return GameFunctions.isPlayerSpectatingOrCreative(context.viewer())
                    ? BodyHudDecision.SHOW
                    : BodyHudDecision.HIDE;
        }
        return GameFunctions.isPlayerSpectatingOrCreative(context.viewer())
                ? BodyHudDecision.SHOW
                : BodyHudDecision.HIDE;
    }

    public static @Nullable Text resolveLine(@NotNull Context context, @NotNull BodyHudLine line) {
        for (Entry<LineProvider> entry : snapshot(LINE_PROVIDERS)) {
            Text result = entry.handler().resolve(context, line);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public static @NotNull List<Text> resolveAdditionalLines(@NotNull Context context) {
        List<Text> result = new ArrayList<>();
        for (Entry<AdditionalLineProvider> entry : snapshot(ADDITIONAL_LINE_PROVIDERS)) {
            List<Text> lines = entry.handler().resolve(context);
            if (lines != null) {
                result.addAll(lines);
            }
        }
        return result;
    }

    public static @Nullable KillerStatus resolveKillerStatus(@NotNull Context context) {
        for (Entry<KillerStatusProvider> entry : snapshot(KILLER_STATUS_PROVIDERS)) {
            KillerStatus result = entry.handler().resolve(context);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 统一尸体射线。尸体隐藏规则会同时影响渲染、准心选中和 HUD，避免出现“看不见但仍能读信息”。
     */
    public static @Nullable PlayerBodyEntity findLookedAtBody(@NotNull ClientPlayerEntity viewer, float range) {
        Entity source = viewer;
        if (!(ProjectileUtil.getCollision(source,
                entity -> entity instanceof PlayerBodyEntity body && TargetVisibilityApi.canTargetBody(viewer, body),
                range) instanceof EntityHitResult hit)) {
            return null;
        }
        return hit.getEntity() instanceof PlayerBodyEntity body ? body : null;
    }

    public record Context(@NotNull ClientPlayerEntity viewer,
                          @NotNull PlayerBodyEntity body) {
    }

    public enum BodyHudLine {
        NAME,
        DEATH,
        IDENTITY,
        AFFILIATION,
        KILLER
    }

    public enum BodyHudDecision {
        PASS,
        SHOW,
        HIDE,
        OBFUSCATE
    }

    public enum KillerStatus {
        ALIVE,
        DEAD,
        OFFLINE
    }

    @FunctionalInterface
    public interface LineRule {
        @NotNull BodyHudDecision resolve(@NotNull Context context, @NotNull BodyHudLine line);
    }

    @FunctionalInterface
    public interface LineProvider {
        @Nullable Text resolve(@NotNull Context context, @NotNull BodyHudLine line);
    }

    @FunctionalInterface
    public interface AdditionalLineProvider {
        @NotNull List<Text> resolve(@NotNull Context context);
    }

    @FunctionalInterface
    public interface KillerStatusProvider {
        @Nullable KillerStatus resolve(@NotNull Context context);
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

    private static <T> List<Entry<T>> snapshot(List<Entry<T>> entries) {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    private record Entry<T>(@NotNull Identifier id, int priority, long order, @NotNull T handler) {
    }
}
