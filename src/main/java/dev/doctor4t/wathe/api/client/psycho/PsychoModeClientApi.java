package dev.doctor4t.wathe.api.client.psycho;

import dev.doctor4t.ratatouille.client.util.ambience.AmbienceUtil;
import dev.doctor4t.ratatouille.client.util.ambience.BackgroundAmbience;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.appearance.PlayerAppearanceApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeProfile;
import dev.doctor4t.wathe.api.psycho.PsychoVisualSettings;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.index.WatheSounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 疯魔模式客户端表现接入点。
 *
 * <p>这里实现“profile 默认皮肤 + 扩展按优先级覆盖”的双层机制：
 * profile 先给出默认皮肤和 feature 隐藏策略；如果扩展职业需要在特定状态下覆盖，
 * 再注册高 priority 的 visual provider 返回新的 {@link PsychoVisualSettings}。</p>
 */
@Environment(EnvType.CLIENT)
public final class PsychoModeClientApi {
    public static final int DEFAULT_VISUAL_PRIORITY = 0;
    public static final int PLAYER_APPEARANCE_PRIORITY = 10_000;

    private static final List<VisualEntry> VISUAL_PROVIDERS = new ArrayList<>();
    private static final Set<Identifier> REGISTERED_BACKGROUND_SOUNDS = new HashSet<>();
    private static long nextOrder = 0L;
    private static boolean defaultHandlersRegistered = false;

    private PsychoModeClientApi() {
    }

    public static void registerDefaultClientHandlers() {
        if (defaultHandlersRegistered) {
            return;
        }
        defaultHandlersRegistered = true;

        PlayerAppearanceApi.registerPlayerSkin(Wathe.id("psycho/default_player_skin"), PLAYER_APPEARANCE_PRIORITY, PsychoModeClientApi::resolveSkinTextures);
        registerBackgroundAmbience(WatheSounds.AMBIENT_PSYCHO_DRONE, 20);
    }

    public static void registerVisualProvider(@NotNull Identifier id, int priority, @NotNull VisualProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        synchronized (VISUAL_PROVIDERS) {
            VISUAL_PROVIDERS.removeIf(entry -> entry.id().equals(id));
            VISUAL_PROVIDERS.add(new VisualEntry(id, priority, nextOrder++, provider));
            VISUAL_PROVIDERS.sort(Comparator.<VisualEntry>comparingInt(VisualEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(VisualEntry::order).reversed()));
        }
    }

    public static void registerBackgroundAmbience(@NotNull SoundEvent sound, int intervalTicks) {
        Identifier soundId = Registries.SOUND_EVENT.getId(sound);
        if (!REGISTERED_BACKGROUND_SOUNDS.add(soundId)) {
            return;
        }

        /*
         * BackgroundAmbience 本身一次只能绑定一个 SoundEvent。
         * 因此自定义疯魔音乐的扩展，需要在 client initializer 里为自己的 SoundEvent 调用本方法；
         * Wathe 会在 predicate 中统一扫描所有当前激活 profile，只有至少一个 profile 声明播放该声音时才启动。
         */
        AmbienceUtil.registerBackgroundAmbience(new BackgroundAmbience(
                sound,
                player -> PsychoModeApi.shouldPlayBackgroundSound(player.getWorld(), sound),
                intervalTicks
        ));
    }

    public static @Nullable PsychoVisualSettings resolveVisualSettings(@NotNull AbstractClientPlayerEntity player) {
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive()) {
            return null;
        }

        PsychoModeProfile profile = component.getProfile();
        for (VisualEntry entry : visualSnapshot()) {
            PsychoVisualSettings result = entry.provider().resolve(player, component, profile);
            if (result != null) {
                return result;
            }
        }
        return profile.visualSettings();
    }

    public static @Nullable SkinTextures resolveSkinTextures(@NotNull AbstractClientPlayerEntity player) {
        PsychoVisualSettings visualSettings = resolveVisualSettings(player);
        if (visualSettings == null) {
            return null;
        }

        SkinTextures original = PlayerAppearanceApi.resolveOriginalSkinTextures(player.getUuid(), true);
        Identifier texture = visualSettings.texture(original.model() == SkinTextures.Model.SLIM);
        if (texture == null) {
            return null;
        }

        return new SkinTextures(
                texture,
                original.textureUrl(),
                original.capeTexture(),
                original.elytraTexture(),
                original.model(),
                original.secure()
        );
    }

    public static boolean shouldHideFeatures(@NotNull AbstractClientPlayerEntity player) {
        PsychoVisualSettings visualSettings = resolveVisualSettings(player);
        return visualSettings != null && visualSettings.hideFeatures();
    }

    private static List<VisualEntry> visualSnapshot() {
        synchronized (VISUAL_PROVIDERS) {
            return List.copyOf(VISUAL_PROVIDERS);
        }
    }

    @FunctionalInterface
    public interface VisualProvider {
        @Nullable PsychoVisualSettings resolve(@NotNull AbstractClientPlayerEntity player, @NotNull PlayerPsychoComponent component, @NotNull PsychoModeProfile profile);
    }

    private record VisualEntry(@NotNull Identifier id, int priority, long order, @NotNull VisualProvider provider) {
    }
}
