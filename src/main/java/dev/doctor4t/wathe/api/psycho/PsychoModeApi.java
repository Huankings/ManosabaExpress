package dev.doctor4t.wathe.api.psycho;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
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
                .cooldownItem(WatheItems.PSYCHO_MODE)
                .grantItem(new ItemStack(WatheItems.BAT))
                .lockHotbar(true)
                .lockGrantedItems(true)
                .removeGrantedItemsOnEnd(true)
                .selectFirstGrantedItem(true)
                .preventDroppingLockedItems(true)
                .meleeKill(true, GameConstants.DeathReasons.BAT)
                /*
                 * 默认疯魔的伤害武器本质上就是 Wathe 球棒。
                 * 锁栏、结束回收仍然依赖 Wathe 自动写入的 PSYCHO_GRANTED_PROFILE 标记；
                 * 但准心显示和服务端击杀不应完全依赖这个临时标记，否则调试给出的球棒、
                 * 或极少数背包同步/复制导致标记缺失的球棒，会出现“对准正常玩家也没有球棒准心，
                 * 挥击也不触发疯魔击杀”的假失效。
                 */
                .meleeWeaponPredicate((player, stack) -> stack.isOf(WatheItems.BAT))
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

    /**
     * 返回当前已注册疯魔 profile 的只读快照。
     *
     * <p>扩展可从 profile 的 {@link PsychoModeProfile#cooldownItem()} 读取对应疯魔图标，
     * 从而在清理技能冷却时兼容 Wathe 原生和其它扩展注册的专属图标，而不维护易过期的物品清单。</p>
     */
    public static synchronized @NotNull List<PsychoModeProfile> registeredProfiles() {
        ensureInitialized();
        return List.copyOf(PROFILES.values());
    }

    /**
     * 清除玩家所有已注册疯魔 profile 对应图标的物品冷却。
     *
     * <p>没有登记 {@code cooldownItem} 的 profile 会被跳过；多个 profile 共用同一物品时只清理一次。</p>
     *
     * @return 实际检查到的不同冷却物品数量
     */
    public static int clearRegisteredProfileCooldowns(@NotNull PlayerEntity player) {
        java.util.Set<Item> cooldownItems = new java.util.LinkedHashSet<>();
        for (PsychoModeProfile profile : registeredProfiles()) {
            Item cooldownItem = profile.cooldownItem();
            if (cooldownItem != null) {
                cooldownItems.add(cooldownItem);
            }
        }

        for (Item item : cooldownItems) {
            player.getItemCooldownManager().remove(item);
        }
        return cooldownItems.size();
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

    /**
     * 给当前疯魔增加剩余时间，并同步更新 HUD 使用的最大时间。
     *
     * <p>profileId 用来防止延迟事件误给已经切换成另一种疯魔的玩家加时。
     * 返回 {@code false} 表示玩家当前没有处于指定 profile。</p>
     */
    public static boolean addRemainingTicks(@NotNull PlayerEntity player, @NotNull Identifier profileId, int addedTicks) {
        if (addedTicks <= 0) {
            return false;
        }
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive() || !profileId.equals(component.getProfileId())) {
            return false;
        }
        component.addPsychoTicks(addedTicks);
        return true;
    }

    /**
     * 给当前指定疯魔 profile 增加护盾层数。
     *
     * <p>profileId 用来防止延迟事件把护盾加到玩家后来切换的另一种疯魔上；
     * 只接受正数增量，并在极端情况下饱和到整数上限，避免层数溢出成负数。</p>
     *
     * @return 玩家当前确实处于指定 profile 且成功增加护盾时返回 {@code true}
     */
    public static boolean addArmourLayers(@NotNull PlayerEntity player, @NotNull Identifier profileId, int addedLayers) {
        if (addedLayers <= 0) {
            return false;
        }
        PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(player);
        if (!component.isPsychoActive() || !profileId.equals(component.getProfileId())) {
            return false;
        }
        int currentLayers = Math.max(0, component.getArmour());
        int nextLayers = currentLayers > Integer.MAX_VALUE - addedLayers
                ? Integer.MAX_VALUE
                : currentLayers + addedLayers;
        component.setArmour(nextLayers);
        return true;
    }

    /**
     * 暂停当前疯魔并返回可恢复快照。
     *
     * <p>暂停不记录结束回放；profile 临时物品仍按来源标记精确回收，避免暂停期间继续使用实际能力。</p>
     */
    public static @Nullable PsychoModeState pause(@NotNull PlayerEntity player) {
        return PlayerPsychoComponent.KEY.get(player).pausePsycho();
    }

    /**
     * 恢复此前由 {@link #pause(PlayerEntity)} 暂停的同一次疯魔。
     *
     * <p>如果快捷栏已没有足够空间，恢复会失败且不会创建半启动状态；调用方可以保留快照并在后续 tick 重试。</p>
     */
    public static boolean resume(@NotNull PlayerEntity player, @NotNull PsychoModeState state) {
        return PlayerPsychoComponent.KEY.get(player).resumePsycho(state);
    }

    /**
     * 将 profile 对应物品的当前冷却托底到指定 tick。
     *
     * <p>已有冷却更长时保持原值；低于下限时才延长。profile 没有登记 cooldownItem 时返回 false。</p>
     */
    public static boolean ensureMinimumCooldown(@NotNull PlayerEntity player, @NotNull Identifier profileId, int minimumTicks) {
        PsychoModeProfile profile = getProfile(profileId);
        return profile != null && ensureMinimumCooldown(player, profile, minimumTicks);
    }

    public static boolean ensureMinimumCooldown(@NotNull PlayerEntity player, @NotNull PsychoModeProfile profile, int minimumTicks) {
        Item item = profile.cooldownItem();
        if (item == null || minimumTicks <= 0) {
            return false;
        }
        ItemCooldownManager manager = player.getItemCooldownManager();
        ItemCooldownManager.Entry entry = manager.entries.get(item);
        int remainingTicks = entry == null ? 0 : Math.max(0, entry.endTick - manager.tick);
        if (remainingTicks < minimumTicks) {
            manager.set(item, minimumTicks);
        }
        return true;
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
        /*
         * Wathe 原始球棒不应被限制成“只有疯魔激活时才能使用”。
         *
         * 疯魔 profile 负责的是：临时授予/锁定/结束回收球棒、特殊皮肤、护盾、背景音等状态增强；
         * 而“手持 Wathe 球棒可以作为近战击杀武器”本身是球棒基础机制。
         * 因此这里先放行真实球棒，再让扩展 profile 判断彩虹斧等自定义近战武器。
         */
        if (stack.isOf(WatheItems.BAT)) {
            return true;
        }

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
