package dev.doctor4t.wathe.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.ServerConfigHandler;
import net.minecraft.util.Arm;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class PlayerBodyEntity extends LivingEntity {
    public static final UUID FALLBACK_PLAYER_UUID = UUID.fromString("25adae11-cd98-48f4-990b-9fe1b2ee0886");
    private static final TrackedData<Optional<UUID>> PLAYER = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Optional<UUID>> APPEARANCE_PLAYER = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<String> PLAYER_NAME = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> DEATH_REASON = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> PLAYER_ROLE = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Optional<UUID>> KILLER = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<String> KILLER_NAME = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> KILLER_ROLE = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Boolean> SYNTHETIC = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> DEATH_ELAPSED_BASE = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> DEATH_ELAPSED_REVISION = DataTracker.registerData(PlayerBodyEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /**
     * {@link #DEATH_ELAPSED_BASE} 对应的本地实体 age 锚点。
     *
     * <p>死亡经过时间不能继续直接读取原版 {@link #age}：时停者会从历史 NBT 恢复尸体，
     * 但原版 age 不会按尸体语义回退。这里使用“同步基准值 + 每端本地递增”的方式，
     * 正常游戏中不需要每 tick 发网络包；只有回溯或人工重置时间时才同步一次新基准。</p>
     */
    private int deathElapsedAnchorAge = 0;

    public PlayerBodyEntity(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(PLAYER, Optional.empty());
        builder.add(APPEARANCE_PLAYER, Optional.empty());
        builder.add(PLAYER_NAME, "");
        builder.add(DEATH_REASON, "wathe:generic");
        builder.add(PLAYER_ROLE, "wathe:civilian");
        builder.add(KILLER, Optional.empty());
        builder.add(KILLER_NAME, "");
        builder.add(KILLER_ROLE, "");
        builder.add(SYNTHETIC, false);
        builder.add(DEATH_ELAPSED_BASE, 0);
        builder.add(DEATH_ELAPSED_REVISION, 0);
    }

    @Override
    public Iterable<ItemStack> getArmorItems() {
        return null;
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {

    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.dataTracker.set(PLAYER, Optional.of(playerUuid));
    }

    public UUID getPlayerUuid() {
        Optional<UUID> optional = this.dataTracker.get(PLAYER);
        return optional.orElse(FALLBACK_PLAYER_UUID); // Folly default because that's lowkey funny
    }

    /**
     * 设置尸体的视觉外观 UUID。
     *
     * <p>这个值只表示“客户端应该按谁的皮肤渲染尸体”，不会改变尸体真实 owner。
     * 因此验尸、尸袋、回放等玩法逻辑仍然应该继续读取 {@link #getPlayerUuid()}。</p>
     */
    public void setAppearanceUuid(UUID playerUuid) {
        this.dataTracker.set(APPEARANCE_PLAYER, Optional.of(playerUuid));
    }

    public void clearAppearanceUuid() {
        this.dataTracker.set(APPEARANCE_PLAYER, Optional.empty());
    }

    public Optional<UUID> getExplicitAppearanceUuid() {
        return this.dataTracker.get(APPEARANCE_PLAYER);
    }

    public UUID getAppearanceUuid() {
        return this.getExplicitAppearanceUuid().orElseGet(this::getPlayerUuid);
    }

    public void setPlayerNameSnapshot(String playerName) {
        this.dataTracker.set(PLAYER_NAME, playerName == null ? "" : playerName);
    }

    public String getPlayerNameSnapshot() {
        return this.dataTracker.get(PLAYER_NAME);
    }

    public void setDeathReasonId(String deathReasonId) {
        this.dataTracker.set(DEATH_REASON, deathReasonId == null || deathReasonId.isBlank() ? "wathe:generic" : deathReasonId);
    }

    public String getDeathReasonId() {
        return this.dataTracker.get(DEATH_REASON);
    }

    public void setPlayerRoleId(String playerRoleId) {
        this.dataTracker.set(PLAYER_ROLE, playerRoleId == null || playerRoleId.isBlank() ? "wathe:civilian" : playerRoleId);
    }

    public String getPlayerRoleId() {
        return this.dataTracker.get(PLAYER_ROLE);
    }

    public void setKillerSnapshot(@Nullable UUID killerUuid, @Nullable String killerName, @Nullable String killerRoleId) {
        this.dataTracker.set(KILLER, Optional.ofNullable(killerUuid));
        this.dataTracker.set(KILLER_NAME, killerName == null ? "" : killerName);
        this.dataTracker.set(KILLER_ROLE, killerRoleId == null ? "" : killerRoleId);
    }

    public Optional<UUID> getKillerUuid() {
        return this.dataTracker.get(KILLER);
    }

    public String getKillerNameSnapshot() {
        return this.dataTracker.get(KILLER_NAME);
    }

    public String getKillerRoleId() {
        return this.dataTracker.get(KILLER_ROLE);
    }

    public void setSynthetic(boolean synthetic) {
        this.dataTracker.set(SYNTHETIC, synthetic);
    }

    public boolean isSynthetic() {
        return this.dataTracker.get(SYNTHETIC);
    }

    public int getDeathElapsedTicks() {
        return Math.max(0, this.dataTracker.get(DEATH_ELAPSED_BASE) + this.age - this.deathElapsedAnchorAge);
    }

    public void setDeathElapsedTicks(int elapsedTicks) {
        this.dataTracker.set(DEATH_ELAPSED_BASE, Math.max(0, elapsedTicks));
        /*
         * 回溯到 0 tick 时，基准值可能和旧值同为 0；仅写基准不会触发 DataTracker 更新。
         * 单独递增 revision，保证客户端每次时间校准都会重置自己的本地 age 锚点。
         */
        this.dataTracker.set(DEATH_ELAPSED_REVISION, this.dataTracker.get(DEATH_ELAPSED_REVISION) + 1);
        this.deathElapsedAnchorAge = this.age;
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (DEATH_ELAPSED_BASE.equals(data) || DEATH_ELAPSED_REVISION.equals(data)) {
            this.deathElapsedAnchorAge = this.age;
        }
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource damageSource) {
        return !damageSource.isOf(DamageTypes.GENERIC_KILL) && !damageSource.isOf(DamageTypes.OUT_OF_WORLD);
    }

    @Override
    protected void pushAway(Entity entity) {
    }

    @Override
    public void pushAwayFrom(Entity entity) {
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 999999.0);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.getPlayerUuid() != null) {
            nbt.putUuid("Player", this.getPlayerUuid());
        }
        this.getExplicitAppearanceUuid().ifPresent(uuid -> nbt.putUuid("AppearancePlayer", uuid));
        nbt.putString("BodyPlayerName", this.getPlayerNameSnapshot());
        nbt.putString("BodyDeathReason", this.getDeathReasonId());
        nbt.putString("BodyPlayerRole", this.getPlayerRoleId());
        this.getKillerUuid().ifPresent(uuid -> nbt.putUuid("BodyKiller", uuid));
        nbt.putString("BodyKillerName", this.getKillerNameSnapshot());
        nbt.putString("BodyKillerRole", this.getKillerRoleId());
        nbt.putBoolean("BodySynthetic", this.isSynthetic());
        nbt.putInt("BodyDeathElapsedTicks", this.getDeathElapsedTicks());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        UUID uUID;
        if (nbt.containsUuid("Player")) {
            uUID = nbt.getUuid("Player");
        } else {
            String string = nbt.getString("Player");
            uUID = ServerConfigHandler.getPlayerUuidByName(this.getServer(), string);
        }

        if (uUID != null) {
            this.setPlayerUuid(uUID);
        }

        if (nbt.containsUuid("AppearancePlayer")) {
            this.setAppearanceUuid(nbt.getUuid("AppearancePlayer"));
        } else {
            this.clearAppearanceUuid();
        }

        this.setPlayerNameSnapshot(nbt.getString("BodyPlayerName"));
        this.setDeathReasonId(nbt.contains("BodyDeathReason") ? nbt.getString("BodyDeathReason") : "wathe:generic");
        this.setPlayerRoleId(nbt.contains("BodyPlayerRole") ? nbt.getString("BodyPlayerRole") : "wathe:civilian");
        this.setKillerSnapshot(
                nbt.containsUuid("BodyKiller") ? nbt.getUuid("BodyKiller") : null,
                nbt.getString("BodyKillerName"),
                nbt.getString("BodyKillerRole")
        );
        this.setSynthetic(nbt.getBoolean("BodySynthetic"));
        this.setDeathElapsedTicks(nbt.contains("BodyDeathElapsedTicks") ? nbt.getInt("BodyDeathElapsedTicks") : this.age);
    }

}
