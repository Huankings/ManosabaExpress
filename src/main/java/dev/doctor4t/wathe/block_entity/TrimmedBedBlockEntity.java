package dev.doctor4t.wathe.block_entity;

import dev.doctor4t.wathe.index.WatheBlockEntities;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class TrimmedBedBlockEntity extends BlockEntity {
    /**
     * 床上的通用附加效果 ID。
     *
     * <p>例如：
     * wathe:scorpion
     * noellesroles:timed_bomb_bed_embedded</p>
     */
    private String bedEffect;
    /**
     * 这层床效果的放置者 UUID 字符串。
     */
    private String bedEffectOwner;

    public boolean hasScorpion() {
        return Registries.ITEM.getId(WatheItems.SCORPION).toString().equals(this.bedEffect);
    }

    public void setHasScorpion(boolean hasScorpion, @Nullable UUID poisoner) {
        if (hasScorpion) {
            this.setBedEffect(Registries.ITEM.getId(WatheItems.SCORPION).toString(), poisoner == null ? null : poisoner.toString());
        } else if (this.hasScorpion()) {
            this.clearBedEffect();
        }
    }

    public @Nullable UUID getPoisoner() {
        if (!this.hasScorpion() || this.bedEffectOwner == null) {
            return null;
        }
        try {
            return UUID.fromString(this.bedEffectOwner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public @Nullable String getBedEffect() {
        return this.bedEffect;
    }

    public @Nullable String getBedEffectOwner() {
        return this.bedEffectOwner;
    }

    public void setBedEffect(@Nullable String bedEffect, @Nullable String bedEffectOwner) {
        this.bedEffect = bedEffect;
        this.bedEffectOwner = bedEffectOwner;
        sync();
    }

    public void clearBedEffect() {
        this.setBedEffect(null, null);
    }

    public TrimmedBedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static TrimmedBedBlockEntity create(BlockPos pos, BlockState state) {
        return new TrimmedBedBlockEntity(WatheBlockEntities.TRIMMED_BED, pos, state);
    }

    private void sync() {
        if (world != null && !world.isClient) {
            markDirty();
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    @SuppressWarnings("unused")
    public static <T extends BlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T t) {

    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        return createNbt(registryLookup);
    }

    @Override
    public @Nullable Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        if (this.bedEffect != null) {
            nbt.putString("bed_effect", this.bedEffect);
        }
        if (this.bedEffectOwner != null) {
            nbt.putString("bed_effect_owner", this.bedEffectOwner);
        }

        /*
         * 兼容旧世界里“蝎子床”只认 hasScorpion / poisoner 这套键的历史数据。
         * 继续把旧键也写出来，这样就算玩家中途拿旧存档和新 jar 来回切换，床状态也不会丢。
         */
        nbt.putBoolean("hasScorpion", this.hasScorpion());
        UUID poisoner = this.getPoisoner();
        if (poisoner != null) {
            nbt.putUuid("poisoner", poisoner);
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        this.bedEffect = nbt.contains("bed_effect") ? nbt.getString("bed_effect") : null;
        this.bedEffectOwner = nbt.contains("bed_effect_owner") ? nbt.getString("bed_effect_owner") : null;

        /*
         * 旧版床没有 bed_effect 字段，只有 hasScorpion / poisoner。
         * 因此在新字段缺失时，需要把旧蝎子状态平滑迁移到通用床效果字段上。
         */
        if (this.bedEffect == null && nbt.getBoolean("hasScorpion")) {
            this.bedEffect = Registries.ITEM.getId(WatheItems.SCORPION).toString();
            this.bedEffectOwner = nbt.containsUuid("poisoner") ? nbt.getUuid("poisoner").toString() : null;
        }
    }
}
