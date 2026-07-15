package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Wathe 心情 HUD 的公开客户端注册入口。
 *
 * <p>扩展职业模组应该在自己的 {@code ClientModInitializer} 中注册样式，
 * 不再 mixin {@code MoodRenderer#renderKiller}/{@code renderCivilian}。
 */
@Environment(EnvType.CLIENT)
public final class MoodHudApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Map<Role, MoodHudStyle> ROLE_STYLES = new HashMap<>();
    private static final ArrayList<PrioritizedMoodProvider> MOOD_PROVIDERS = new ArrayList<>();
    private static final ArrayList<PrioritizedPsychoProvider> PSYCHO_PROVIDERS = new ArrayList<>();
    private static final Set<GameMode> VISIBLE_GAME_MODES = new HashSet<>();
    private static final ArrayList<PrioritizedGameModePredicate> VISIBLE_GAME_MODE_PREDICATES = new ArrayList<>();

    private MoodHudApi() {
    }

    public static void registerRoleStyle(Role role, MoodHudStyle style) {
        ROLE_STYLES.put(role, style);
    }

    /**
     * 注册一个高优先级动态样式。
     *
     * <p>这个入口用于“当前职业之外的临时覆盖”，比如 Executioner 成功转杀手后，
     * 当前 role 已经变成杀手，但仍希望 1 秒内显示 burn 动画。
     */
    public static void registerMoodProvider(Identifier id, int priority, MoodStyleProvider provider) {
        MOOD_PROVIDERS.removeIf(entry -> entry.id.equals(id));
        MOOD_PROVIDERS.add(new PrioritizedMoodProvider(id, priority, provider));
        MOOD_PROVIDERS.sort(Comparator.comparingInt(PrioritizedMoodProvider::priority).reversed());
    }

    public static void registerPsychoStyle(Identifier id, int priority, PsychoStyleProvider provider) {
        PSYCHO_PROVIDERS.removeIf(entry -> entry.id.equals(id));
        PSYCHO_PROVIDERS.add(new PrioritizedPsychoProvider(id, priority, provider));
        PSYCHO_PROVIDERS.sort(Comparator.comparingInt(PrioritizedPsychoProvider::priority).reversed());
    }

    public static void registerVisibleGameMode(GameMode gameMode) {
        VISIBLE_GAME_MODES.add(gameMode);
    }

    public static void registerVisibleGameModePredicate(Identifier id, int priority, Predicate<GameMode> predicate) {
        VISIBLE_GAME_MODE_PREDICATES.removeIf(entry -> entry.id.equals(id));
        VISIBLE_GAME_MODE_PREDICATES.add(new PrioritizedGameModePredicate(id, priority, predicate));
        VISIBLE_GAME_MODE_PREDICATES.sort(Comparator.comparingInt(PrioritizedGameModePredicate::priority).reversed());
    }

    public static boolean shouldRenderInGameMode(GameMode gameMode) {
        if (gameMode == WatheGameModes.MURDER || VISIBLE_GAME_MODES.contains(gameMode)) {
            return true;
        }
        for (PrioritizedGameModePredicate entry : VISIBLE_GAME_MODE_PREDICATES) {
            if (entry.predicate.test(gameMode)) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable MoodHudStyle resolveMoodStyle(MoodHudContext context) {
        for (PrioritizedMoodProvider entry : MOOD_PROVIDERS) {
            MoodHudStyle style = entry.provider.getStyle(context);
            if (style != null) {
                return style;
            }
        }
        return ROLE_STYLES.get(context.role());
    }

    public static PsychoMoodHudStyle resolvePsychoStyle(MoodHudContext context, PlayerPsychoComponent psycho) {
        for (PrioritizedPsychoProvider entry : PSYCHO_PROVIDERS) {
            PsychoMoodHudStyle style = entry.provider.getStyle(context, psycho);
            if (style != null) {
                return style;
            }
        }
        return PsychoMoodHudStyle.defaults();
    }

    @FunctionalInterface
    public interface MoodStyleProvider {
        @Nullable MoodHudStyle getStyle(MoodHudContext context);
    }

    @FunctionalInterface
    public interface PsychoStyleProvider {
        @Nullable PsychoMoodHudStyle getStyle(MoodHudContext context, PlayerPsychoComponent psycho);
    }

    private record PrioritizedMoodProvider(Identifier id, int priority, MoodStyleProvider provider) {
    }

    private record PrioritizedPsychoProvider(Identifier id, int priority, PsychoStyleProvider provider) {
    }

    private record PrioritizedGameModePredicate(Identifier id, int priority, Predicate<GameMode> predicate) {
    }
}
