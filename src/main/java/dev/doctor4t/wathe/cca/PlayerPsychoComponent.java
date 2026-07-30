package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeProfile;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

public class PlayerPsychoComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<PlayerPsychoComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("psycho"), PlayerPsychoComponent.class);
    private final PlayerEntity player;
    public int psychoTicks = 0;
    public int armour = 1;
    private int maxPsychoTicks = 1;
    private int initialArmour = 1;
    private Identifier profileId = PsychoModeApi.DEFAULT_PROFILE_ID;

    public PlayerPsychoComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        KEY.sync(this.player);
    }

    public void reset() {
        this.stopPsycho(false);
        this.sync();
    }

    @Override
    public void clientTick() {
        if (this.psychoTicks <= 0) return;
        this.psychoTicks--;
        if (PsychoModeApi.isLockedItem(this.player, this.player.getMainHandStack())) return;
        if (GameFunctions.isPlayerAliveAndSurvival(player)) {
            int lockedSlot = PsychoModeApi.findLockedHotbarSlot(this.player);
            if (lockedSlot >= 0) {
                this.player.getInventory().selectedSlot = lockedSlot;
            }
        }
    }

    @Override
    public void serverTick() {
        if (this.psychoTicks <= 0) return;
//        if (this.psychoTicks % 20 == 0) this.player.sendMessage(Text.translatable("game.psycho_mode.time", this.psychoTicks / 20).withColor(Colors.RED), true);
        if (--this.psychoTicks == 0) {
//            this.player.sendMessage(Text.translatable("game.psycho_mode.over").withColor(Colors.RED), true);
            this.stopPsycho();
        }

        this.sync();
    }

    public boolean startPsycho() {
        return PsychoModeApi.start(this.player);
    }

    public boolean startPsycho(@NotNull Identifier profileId) {
        return PsychoModeApi.start(this.player, profileId);
    }

    public boolean startPsycho(@NotNull PsychoModeProfile profile) {
        if (this.player.getWorld().isClient || this.isPsychoActive()) {
            return false;
        }

        if (!this.hasFreeHotbarSlots(profile.grantedItems().size())) {
            return false;
        }

        /*
         * 所有临时物品都由 API 在这里统一打 profile 标记。
         * 扩展职业不要再自己在 stop 时按 Item 类型大范围 remove，避免误删玩家已有物品。
         */
        int firstGrantedSlot = -1;
        for (ItemStack template : profile.grantedItems()) {
            ItemStack granted = template.copy();
            PsychoModeApi.markGrantedItem(profile, granted);
            int insertedSlot = this.insertGrantedStack(granted);
            if (firstGrantedSlot < 0 && insertedSlot >= 0) {
                firstGrantedSlot = insertedSlot;
            }
        }

        this.profileId = profile.id();
        this.maxPsychoTicks = profile.durationTicks();
        this.initialArmour = profile.armour();
        this.psychoTicks = profile.durationTicks();
        this.armour = profile.armour();

        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
        gameWorldComponent.setPsychosActive(gameWorldComponent.getPsychosActive() + 1);

        if (profile.selectFirstGrantedItem()) {
            int lockedSlot = PsychoModeApi.findLockedHotbarSlot(this.player);
            if (lockedSlot >= 0) {
                this.player.getInventory().selectedSlot = lockedSlot;
            } else if (firstGrantedSlot >= 0) {
                this.player.getInventory().selectedSlot = firstGrantedSlot;
            }
        }
        this.player.playerScreenHandler.sendContentUpdates();
        this.sync();
        return true;
    }

    private boolean hasFreeHotbarSlots(int requiredSlots) {
        if (requiredSlots <= 0) {
            return true;
        }
        int freeSlots = 0;
        for (int i = 0; i < 9; i++) {
            if (this.player.getInventory().getStack(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots >= requiredSlots;
    }

    private int insertGrantedStack(ItemStack stack) {
        for (int i = 0; i < 9; i++) {
            if (this.player.getInventory().getStack(i).isEmpty()) {
                this.player.getInventory().setStack(i, stack);
                return i;
            }
        }
        /*
         * start 前已经检查过空间；这里保留兜底，防止其它模组在同一 tick 中改背包时留下半启动状态。
         */
        ShopEntry.insertStackInFreeSlot(this.player, stack);
        return -1;
    }

    public void stopPsycho() {
        this.stopPsycho(true);
    }

    public void stopPsycho(boolean recordReplay) {
        boolean wasActive = this.psychoTicks > 0;
        PsychoModeProfile profile = this.getProfile();
        if (wasActive) {
            GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(this.player.getWorld());
            gameWorldComponent.setPsychosActive(gameWorldComponent.getPsychosActive() - 1);
        }
        this.psychoTicks = 0;
        if (profile.removeGrantedItemsOnEnd()) {
            this.player.getInventory().remove(
                    itemStack -> PsychoModeApi.isGrantedForProfile(itemStack, profile.id()),
                    Integer.MAX_VALUE,
                    this.player.playerScreenHandler.getCraftingInput()
            );
        }

        if (wasActive && recordReplay && this.player instanceof ServerPlayerEntity serverPlayer) {
            dev.doctor4t.wathe.record.GameRecordManager.recordGlobalEvent(
                    serverPlayer.getServerWorld(),
                    profile.endEventId(),
                    serverPlayer,
                    PsychoModeApi.createModeReplayData(profile)
            );
        }
        this.profileId = PsychoModeApi.DEFAULT_PROFILE_ID;
        this.maxPsychoTicks = Math.max(1, profile.durationTicks());
        this.initialArmour = profile.armour();
        this.player.playerScreenHandler.sendContentUpdates();
        this.sync();
    }

    public int getArmour() {
        return this.armour;
    }

    public void setArmour(int armour) {
        this.armour = armour;
        this.sync();
    }

    public int getPsychoTicks() {
        return this.psychoTicks;
    }

    public void setPsychoTicks(int ticks) {
        this.psychoTicks = ticks;
        this.maxPsychoTicks = Math.max(this.maxPsychoTicks, ticks);
        this.sync();
    }

    public boolean isPsychoActive() {
        return this.psychoTicks > 0;
    }

    public int getMaxPsychoTicks() {
        return Math.max(1, this.maxPsychoTicks);
    }

    public int getInitialArmour() {
        return Math.max(0, this.initialArmour);
    }

    public Identifier getProfileId() {
        return this.profileId == null ? PsychoModeApi.DEFAULT_PROFILE_ID : this.profileId;
    }

    public PsychoModeProfile getProfile() {
        return PsychoModeApi.getProfileOrDefault(this.getProfileId());
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putInt("psychoTicks", this.psychoTicks);
        tag.putInt("armour", this.armour);
        tag.putInt("maxPsychoTicks", this.maxPsychoTicks);
        tag.putInt("initialArmour", this.initialArmour);
        tag.putString("profileId", this.getProfileId().toString());
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.psychoTicks = tag.contains("psychoTicks") ? tag.getInt("psychoTicks") : 0;
        this.armour = tag.contains("armour") ? tag.getInt("armour") : 1;
        this.maxPsychoTicks = tag.contains("maxPsychoTicks") ? Math.max(1, tag.getInt("maxPsychoTicks")) : Math.max(1, this.psychoTicks);
        this.initialArmour = tag.contains("initialArmour") ? Math.max(0, tag.getInt("initialArmour")) : Math.max(0, this.armour);
        String rawProfileId = tag.getString("profileId");
        Identifier parsedProfileId = rawProfileId == null || rawProfileId.isEmpty() ? null : Identifier.tryParse(rawProfileId);
        this.profileId = parsedProfileId == null ? PsychoModeApi.DEFAULT_PROFILE_ID : parsedProfileId;
    }
}
