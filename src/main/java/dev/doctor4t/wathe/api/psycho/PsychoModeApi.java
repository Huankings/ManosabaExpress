package dev.doctor4t.wathe.api.psycho;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 疯魔模式公开机制入口。
 *
 * <p>扩展职业接入时应先注册 {@link PsychoModeProfile}，再通过
 * {@link #start(PlayerEntity, Identifier)} 启动。Wathe 会统一负责：
 * 授予/回收临时物品、锁快捷栏、护盾消耗、音效、皮肤、HUD 时长和结束回放。</p>
 */
public final class PsychoModeApi {
    public static final Identifier DEFAULT_PROFILE_ID = Wathe.id("psycho_mode");
    public static final String DEFAULT_MODE_NAME_TRANSLATION_KEY = "psycho_mode.wathe.default";
    public static final String DEFAULT_SHIELD_NAME_TRANSLATION_KEY = "psycho_shield.wathe.default";
    public static final String REPLAY_MODE_ID_KEY = "psycho_mode";
    public static final String REPLAY_MODE_NAME_KEY = "psycho_mode_name_key";
    public static final String REPLAY_SHIELD_NAME_KEY = "psycho_shield_name_key";
    public static final int DEFAULT_PRIORITY = 0;

    private static final Map<Identifier, PsychoModeProfile> PROFILES = new LinkedHashMap<>();
    private static final List<StartProfileEntry> START_PROFILE_PROVIDERS = new ArrayList<>();
    private static final List<ShieldRuleEntry> SHIELD_RULES = new ArrayList<>();
    private static long nextOrder = 0L;
    private static boolean initialized = false;

    private PsychoModeApi() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        registerProfile(createDefaultProfile());
    }

    public static @NotNull PsychoModeProfile createDefaultProfile() {
        return PsychoModeProfile.builder(DEFAULT_PROFILE_ID)
                .nameTranslationKey(DEFAULT_MODE_NAME_TRANSLATION_KEY)
                .shieldNameTranslationKey(DEFAULT_SHIELD_NAME_TRANSLATION_KEY)
                .durationTicks(GameConstants.PSYCHO_TIMER)
                .armour(GameConstants.PSYCHO_MODE_ARMOUR)
                .grantItem(new ItemStack(WatheItems.BAT))
                .lockHotbar(true)
                .lockGrantedItems(true)
                .removeGrantedItemsOnEnd(true)
                .selectFirstGrantedItem(true)
                .preventDroppingLockedItems(true)
                .meleeKill(true, GameConstants.DeathReasons.BAT)
                .shieldSourceId(Wathe.id("psycho_mode"))
                .endEventId(Wathe.id("psycho_mode_end"))
                .hitSound(WatheSounds.ITEM_BAT_HIT)
                .shieldSound(WatheSounds.ITEM_PSYCHO_ARMOUR)
                .backgroundSound(WatheSounds.AMBIENT_PSYCHO_DRONE, true)
                .visualSettings(PsychoVisualSettings.skin(
                        Wathe.id("textures/entity/psycho.png"),
                        Wathe.id("textures/entity/psycho_thin.png"),
                        true
                ))
                .build();
    }

    public static synchronized void registerProfile(@NotNull PsychoModeProfile profile) {
        Objects.requireNonNull(profile, "profile");
        PROFILES.put(profile.id(), profile);
    }

    public static synchronized @Nullable PsychoModeProfile getProfile(@NotNull Identifier id) {
        ensureInitialized();
        return PROFILES.get(id);
    }

    public static synchronized @NotNull PsychoModeProfile getProfileOrDefault(@Nullable Identifier id) {
        ensureInitialized();
        PsychoModeProfile profile = id == null ? null : PROFILES.get(id);
        return profile != null ? profile : PROFILES.get(DEFAULT_PROFILE_ID);
    }

    public static boolean start(@NotNull PlayerEntity player) {
        return start(player, DEFAULT_PROFILE_ID);
    }

    public static boolean start(@NotNull PlayerEntity player, @NotNull Identifier profileId) {
        return PlayerPsychoComponent.KEY.get(player).startPsycho(resolveStartProfile(player, getProfileOrDefault(profileId)));
    }

    public static boolean start(@NotNull PlayerEntity player, @NotNull PsychoModeProfile profile) {
        return PlayerPsychoComponent.KEY.get(player).startPsycho(resolveStartProfile(player, profile));
    }

    public static void stop(@NotNull PlayerEntity player) {
        stop(player, true);
    }

    public static void stop(@NotNull PlayerEntity player, boolean recordReplay) {
        /*
         * 公开 stop 入口后，扩展职业在“召集、转职、强制重置、模式提前结束”
         * 这类场景里不需要再直接依赖 PlayerPsychoComponent 的内部实现。
         * recordReplay 参数保留给回合清理/启动失败回滚使用，避免产生误导性的结束回放。
         */
        PlayerPsychoComponent.KEY.get(player).stopPsycho(recordReplay);
    }

    public static boolean isActive(@Nullable PlayerEntity player) {
        return player != null && PlayerPsychoComponent.KEY.get(player).isPsychoActive();
    }

    public static boolean isActive(@Nullable PlayerEntity player, @NotNull Identifier profileId) {
        if (player == null) {
            return false;
        }
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        return component.isPsychoActive() && profileId.equals(component.getProfileId());
    }

    public static @Nullable PsychoModeProfile getActiveProfile(@Nullable PlayerEntity player) {
        if (player == null) {
            return null;
        }
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        return component.isPsychoActive() ? component.getProfile() : null;
    }

    public static int getRemainingTicks(@Nullable PlayerEntity player) {
        if (player == null) {
            return 0;
        }
        return Math.max(0, PlayerPsychoComponent.KEY.get(player).getPsychoTicks());
    }

    public static int getArmour(@Nullable PlayerEntity player) {
        if (player == null) {
            return 0;
        }
        return Math.max(0, PlayerPsychoComponent.KEY.get(player).getArmour());
    }

    public static boolean isLockedItem(@NotNull PlayerEntity player, @NotNull ItemStack stack) {
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive()) {
            return false;
        }
        PsychoModeProfile profile = component.getProfile();
        return profile.lockHotbar() && profile.isLockedItem(player, stack);
    }

    public static int findLockedHotbarSlot(@NotNull PlayerEntity player) {
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive()) {
            return -1;
        }
        PsychoModeProfile profile = component.getProfile();
        if (!profile.lockHotbar()) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (profile.isLockedItem(player, player.getInventory().getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean shouldPreventDrop(@NotNull PlayerEntity player, @NotNull ItemStack stack) {
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive()) {
            return false;
        }
        PsychoModeProfile profile = component.getProfile();
        return profile.preventDroppingLockedItems() && profile.isLockedItem(player, stack);
    }

    public static boolean isMeleeKillWeapon(@NotNull PlayerEntity player, @NotNull ItemStack stack) {
        PsychoModeProfile profile = getActiveProfile(player);
        return profile != null && profile.isMeleeWeapon(player, stack);
    }

    public static @Nullable SoundEvent getMeleeHitSound(@NotNull PlayerEntity player, @NotNull ItemStack stack) {
        PsychoModeProfile profile = getActiveProfile(player);
        if (profile == null || !profile.isMeleeWeapon(player, stack)) {
            return null;
        }
        return profile.hitSound();
    }

    public static void markGrantedItem(@NotNull PsychoModeProfile profile, @NotNull ItemStack stack) {
        stack.set(WatheDataComponentTypes.PSYCHO_GRANTED_PROFILE, profile.id().toString());
    }

    public static boolean isGrantedForProfile(@NotNull ItemStack stack, @NotNull Identifier profileId) {
        String grantedProfile = stack.getOrDefault(WatheDataComponentTypes.PSYCHO_GRANTED_PROFILE, null);
        return profileId.toString().equals(grantedProfile);
    }

    public static NbtCompound createModeReplayData(@NotNull PsychoModeProfile profile) {
        NbtCompound data = new NbtCompound();
        putModeReplayData(data, profile);
        return data;
    }

    public static void putModeReplayData(@NotNull NbtCompound data, @NotNull PsychoModeProfile profile) {
        data.putString(REPLAY_MODE_ID_KEY, profile.id().toString());
        data.putString(REPLAY_MODE_NAME_KEY, profile.nameTranslationKey());
        data.putString(REPLAY_SHIELD_NAME_KEY, profile.shieldNameTranslationKey());
    }

    public static String resolveModeNameTranslationKey(@NotNull NbtCompound data) {
        String modeNameKey = data.getString(REPLAY_MODE_NAME_KEY);
        return modeNameKey == null || modeNameKey.isEmpty() ? DEFAULT_MODE_NAME_TRANSLATION_KEY : modeNameKey;
    }

    public static String resolveShieldNameTranslationKey(@NotNull NbtCompound data) {
        String shieldNameKey = data.getString(REPLAY_SHIELD_NAME_KEY);
        return shieldNameKey == null || shieldNameKey.isEmpty() ? DEFAULT_SHIELD_NAME_TRANSLATION_KEY : shieldNameKey;
    }

    public static PsychoShieldResult resolveShield(@NotNull PsychoShieldContext context) {
        for (ShieldRuleEntry entry : shieldRuleSnapshot()) {
            PsychoShieldResult result = entry.handler().resolve(context);
            if (result != null && result != PsychoShieldResult.PASS) {
                return result;
            }
        }
        return context.component().getArmour() > 0 ? PsychoShieldResult.BLOCK : PsychoShieldResult.PASS;
    }

    public static void registerShieldRule(@NotNull Identifier id, int priority, @NotNull PsychoShieldRule handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (SHIELD_RULES) {
            SHIELD_RULES.removeIf(entry -> entry.id().equals(id));
            SHIELD_RULES.add(new ShieldRuleEntry(id, priority, nextOrder++, handler));
            SHIELD_RULES.sort(Comparator.<ShieldRuleEntry>comparingInt(ShieldRuleEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ShieldRuleEntry::order).reversed()));
        }
    }

    public static void registerStartProfileProvider(@NotNull Identifier id, int priority, @NotNull StartProfileProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        synchronized (START_PROFILE_PROVIDERS) {
            START_PROFILE_PROVIDERS.removeIf(entry -> entry.id().equals(id));
            START_PROFILE_PROVIDERS.add(new StartProfileEntry(id, priority, nextOrder++, provider));
            START_PROFILE_PROVIDERS.sort(Comparator.<StartProfileEntry>comparingInt(StartProfileEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(StartProfileEntry::order).reversed()));
        }
    }

    public static boolean shouldPlayBackgroundSound(@Nullable World world, @NotNull SoundEvent sound) {
        if (world == null) {
            return false;
        }
        for (PlayerEntity player : world.getPlayers()) {
            PsychoModeProfile profile = getActiveProfile(player);
            if (profile == null || !profile.playBackgroundSound() || profile.backgroundSound() == null) {
                continue;
            }
            if (profile.backgroundSound() == sound || Registries.SOUND_EVENT.getId(profile.backgroundSound()).equals(Registries.SOUND_EVENT.getId(sound))) {
                return true;
            }
        }
        return false;
    }

    private static PsychoModeProfile resolveStartProfile(PlayerEntity player, PsychoModeProfile requestedProfile) {
        for (StartProfileEntry entry : startProfileSnapshot()) {
            PsychoModeProfile result = entry.provider().resolve(player, requestedProfile);
            if (result != null) {
                return result;
            }
        }
        return requestedProfile;
    }

    private static List<StartProfileEntry> startProfileSnapshot() {
        synchronized (START_PROFILE_PROVIDERS) {
            return List.copyOf(START_PROFILE_PROVIDERS);
        }
    }

    private static List<ShieldRuleEntry> shieldRuleSnapshot() {
        synchronized (SHIELD_RULES) {
            return List.copyOf(SHIELD_RULES);
        }
    }

    private static synchronized void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    @FunctionalInterface
    public interface StartProfileProvider {
        @Nullable PsychoModeProfile resolve(@NotNull PlayerEntity player, @NotNull PsychoModeProfile requestedProfile);
    }

    @FunctionalInterface
    public interface PsychoShieldRule {
        @NotNull PsychoShieldResult resolve(@NotNull PsychoShieldContext context);
    }

    private record StartProfileEntry(@NotNull Identifier id, int priority, long order, @NotNull StartProfileProvider provider) {
    }

    private record ShieldRuleEntry(@NotNull Identifier id, int priority, long order, @NotNull PsychoShieldRule handler) {
    }
}
