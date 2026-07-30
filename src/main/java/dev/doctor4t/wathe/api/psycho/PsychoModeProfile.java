package dev.doctor4t.wathe.api.psycho;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一种可启动的疯魔模式定义。
 *
 * <p>Wathe 原生疯魔、扩展职业的悬赏模式、静音疯魔、特殊皮肤疯魔都应该表达为 profile。
 * 玩家组件只同步 profile id、剩余时间和护盾数；真正的规则和资源都从注册表查询，
 * 这样后续扩展只需要注册自己的 profile，不需要 mixin {@code PlayerPsychoComponent}。</p>
 */
public final class PsychoModeProfile {
    private final Identifier id;
    private final String nameTranslationKey;
    private final String shieldNameTranslationKey;
    private final int durationTicks;
    private final int armour;
    private final List<ItemStack> grantedItems;
    private final boolean lockHotbar;
    private final boolean lockGrantedItems;
    private final boolean removeGrantedItemsOnEnd;
    private final boolean selectFirstGrantedItem;
    private final boolean preventDroppingLockedItems;
    private final boolean meleeKillEnabled;
    private final Identifier meleeDeathReason;
    private final Identifier shieldSourceId;
    private final Identifier endEventId;
    private final @Nullable SoundEvent hitSound;
    private final @Nullable SoundEvent shieldSound;
    private final @Nullable SoundEvent backgroundSound;
    private final boolean playBackgroundSound;
    private final PsychoVisualSettings visualSettings;
    private final @Nullable PsychoItemPredicate lockedItemPredicate;
    private final @Nullable PsychoItemPredicate meleeWeaponPredicate;

    private PsychoModeProfile(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.nameTranslationKey = Objects.requireNonNull(builder.nameTranslationKey, "nameTranslationKey");
        this.shieldNameTranslationKey = Objects.requireNonNull(builder.shieldNameTranslationKey, "shieldNameTranslationKey");
        this.durationTicks = Math.max(1, builder.durationTicks);
        this.armour = Math.max(0, builder.armour);
        this.grantedItems = copyStacks(builder.grantedItems);
        this.lockHotbar = builder.lockHotbar;
        this.lockGrantedItems = builder.lockGrantedItems;
        this.removeGrantedItemsOnEnd = builder.removeGrantedItemsOnEnd;
        this.selectFirstGrantedItem = builder.selectFirstGrantedItem;
        this.preventDroppingLockedItems = builder.preventDroppingLockedItems;
        this.meleeKillEnabled = builder.meleeKillEnabled;
        this.meleeDeathReason = Objects.requireNonNull(builder.meleeDeathReason, "meleeDeathReason");
        this.shieldSourceId = Objects.requireNonNull(builder.shieldSourceId, "shieldSourceId");
        this.endEventId = Objects.requireNonNull(builder.endEventId, "endEventId");
        this.hitSound = builder.hitSound;
        this.shieldSound = builder.shieldSound;
        this.backgroundSound = builder.backgroundSound;
        this.playBackgroundSound = builder.playBackgroundSound;
        this.visualSettings = Objects.requireNonNull(builder.visualSettings, "visualSettings");
        this.lockedItemPredicate = builder.lockedItemPredicate;
        this.meleeWeaponPredicate = builder.meleeWeaponPredicate;
    }

    public static Builder builder(@NotNull Identifier id) {
        return new Builder(id);
    }

    public static Builder copyOf(@NotNull PsychoModeProfile profile, @NotNull Identifier newId) {
        return new Builder(newId)
                .nameTranslationKey(profile.nameTranslationKey)
                .shieldNameTranslationKey(profile.shieldNameTranslationKey)
                .durationTicks(profile.durationTicks)
                .armour(profile.armour)
                .grantedItems(profile.grantedItems())
                .lockHotbar(profile.lockHotbar)
                .lockGrantedItems(profile.lockGrantedItems)
                .removeGrantedItemsOnEnd(profile.removeGrantedItemsOnEnd)
                .selectFirstGrantedItem(profile.selectFirstGrantedItem)
                .preventDroppingLockedItems(profile.preventDroppingLockedItems)
                .meleeKill(profile.meleeKillEnabled, profile.meleeDeathReason)
                .shieldSourceId(profile.shieldSourceId)
                .endEventId(profile.endEventId)
                .hitSound(profile.hitSound)
                .shieldSound(profile.shieldSound)
                .backgroundSound(profile.backgroundSound, profile.playBackgroundSound)
                .visualSettings(profile.visualSettings)
                .lockedItemPredicate(profile.lockedItemPredicate)
                .meleeWeaponPredicate(profile.meleeWeaponPredicate);
    }

    public Identifier id() {
        return this.id;
    }

    public String nameTranslationKey() {
        return this.nameTranslationKey;
    }

    public String shieldNameTranslationKey() {
        return this.shieldNameTranslationKey;
    }

    public int durationTicks() {
        return this.durationTicks;
    }

    public int armour() {
        return this.armour;
    }

    public List<ItemStack> grantedItems() {
        return copyStacks(this.grantedItems);
    }

    public boolean lockHotbar() {
        return this.lockHotbar;
    }

    public boolean removeGrantedItemsOnEnd() {
        return this.removeGrantedItemsOnEnd;
    }

    public boolean selectFirstGrantedItem() {
        return this.selectFirstGrantedItem;
    }

    public boolean preventDroppingLockedItems() {
        return this.preventDroppingLockedItems;
    }

    public boolean meleeKillEnabled() {
        return this.meleeKillEnabled;
    }

    public Identifier meleeDeathReason() {
        return this.meleeDeathReason;
    }

    public Identifier shieldSourceId() {
        return this.shieldSourceId;
    }

    public Identifier endEventId() {
        return this.endEventId;
    }

    public @Nullable SoundEvent hitSound() {
        return this.hitSound;
    }

    public @Nullable SoundEvent shieldSound() {
        return this.shieldSound;
    }

    public @Nullable SoundEvent backgroundSound() {
        return this.backgroundSound;
    }

    public boolean playBackgroundSound() {
        return this.playBackgroundSound;
    }

    public PsychoVisualSettings visualSettings() {
        return this.visualSettings;
    }

    public boolean isLockedItem(PlayerEntity player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (this.lockedItemPredicate != null && this.lockedItemPredicate.test(player, stack)) {
            return true;
        }
        return this.lockGrantedItems && PsychoModeApi.isGrantedForProfile(stack, this.id);
    }

    public boolean isMeleeWeapon(PlayerEntity player, ItemStack stack) {
        if (!this.meleeKillEnabled || stack.isEmpty()) {
            return false;
        }
        if (this.meleeWeaponPredicate != null) {
            return this.meleeWeaponPredicate.test(player, stack);
        }
        return this.isLockedItem(player, stack);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        return List.copyOf(copy);
    }

    public static final class Builder {
        private final Identifier id;
        private String nameTranslationKey;
        private String shieldNameTranslationKey;
        private int durationTicks = 1;
        private int armour = 0;
        private final List<ItemStack> grantedItems = new ArrayList<>();
        private boolean lockHotbar = true;
        private boolean lockGrantedItems = true;
        private boolean removeGrantedItemsOnEnd = true;
        private boolean selectFirstGrantedItem = true;
        private boolean preventDroppingLockedItems = true;
        private boolean meleeKillEnabled = false;
        private Identifier meleeDeathReason = Wathe.id("bat_hit");
        private Identifier shieldSourceId = Wathe.id("psycho_mode");
        private Identifier endEventId = Wathe.id("psycho_mode_end");
        private @Nullable SoundEvent hitSound;
        private @Nullable SoundEvent shieldSound;
        private @Nullable SoundEvent backgroundSound;
        private boolean playBackgroundSound = true;
        private PsychoVisualSettings visualSettings = PsychoVisualSettings.none();
        private @Nullable PsychoItemPredicate lockedItemPredicate;
        private @Nullable PsychoItemPredicate meleeWeaponPredicate;

        private Builder(Identifier id) {
            this.id = id;
            this.nameTranslationKey = "psycho_mode." + id.getNamespace() + "." + id.getPath();
            this.shieldNameTranslationKey = "psycho_shield." + id.getNamespace() + "." + id.getPath();
        }

        public Builder nameTranslationKey(String nameTranslationKey) {
            this.nameTranslationKey = nameTranslationKey;
            return this;
        }

        public Builder shieldNameTranslationKey(String shieldNameTranslationKey) {
            this.shieldNameTranslationKey = shieldNameTranslationKey;
            return this;
        }

        public Builder durationTicks(int durationTicks) {
            this.durationTicks = durationTicks;
            return this;
        }

        public Builder armour(int armour) {
            this.armour = armour;
            return this;
        }

        public Builder grantItem(ItemStack stack) {
            if (!stack.isEmpty()) {
                this.grantedItems.add(stack.copy());
            }
            return this;
        }

        public Builder grantedItems(List<ItemStack> stacks) {
            this.grantedItems.clear();
            for (ItemStack stack : stacks) {
                this.grantItem(stack);
            }
            return this;
        }

        public Builder lockHotbar(boolean lockHotbar) {
            this.lockHotbar = lockHotbar;
            return this;
        }

        public Builder lockGrantedItems(boolean lockGrantedItems) {
            this.lockGrantedItems = lockGrantedItems;
            return this;
        }

        public Builder removeGrantedItemsOnEnd(boolean removeGrantedItemsOnEnd) {
            this.removeGrantedItemsOnEnd = removeGrantedItemsOnEnd;
            return this;
        }

        public Builder selectFirstGrantedItem(boolean selectFirstGrantedItem) {
            this.selectFirstGrantedItem = selectFirstGrantedItem;
            return this;
        }

        public Builder preventDroppingLockedItems(boolean preventDroppingLockedItems) {
            this.preventDroppingLockedItems = preventDroppingLockedItems;
            return this;
        }

        public Builder meleeKill(boolean meleeKillEnabled, Identifier meleeDeathReason) {
            this.meleeKillEnabled = meleeKillEnabled;
            this.meleeDeathReason = meleeDeathReason;
            return this;
        }

        public Builder shieldSourceId(Identifier shieldSourceId) {
            this.shieldSourceId = shieldSourceId;
            return this;
        }

        public Builder endEventId(Identifier endEventId) {
            this.endEventId = endEventId;
            return this;
        }

        public Builder hitSound(@Nullable SoundEvent hitSound) {
            this.hitSound = hitSound;
            return this;
        }

        public Builder shieldSound(@Nullable SoundEvent shieldSound) {
            this.shieldSound = shieldSound;
            return this;
        }

        public Builder backgroundSound(@Nullable SoundEvent backgroundSound, boolean playBackgroundSound) {
            this.backgroundSound = backgroundSound;
            this.playBackgroundSound = playBackgroundSound;
            return this;
        }

        public Builder visualSettings(PsychoVisualSettings visualSettings) {
            this.visualSettings = visualSettings;
            return this;
        }

        public Builder lockedItemPredicate(@Nullable PsychoItemPredicate lockedItemPredicate) {
            this.lockedItemPredicate = lockedItemPredicate;
            return this;
        }

        public Builder meleeWeaponPredicate(@Nullable PsychoItemPredicate meleeWeaponPredicate) {
            this.meleeWeaponPredicate = meleeWeaponPredicate;
            return this;
        }

        public PsychoModeProfile build() {
            return new PsychoModeProfile(this);
        }
    }
}
