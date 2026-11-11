package dev.doctor4t.wathe.block_entity;

import dev.doctor4t.wathe.block.DoorPartBlock;
import dev.doctor4t.wathe.api.door.DoorApi;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheSounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.AnimationState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public abstract class DoorBlockEntity extends SyncingBlockEntity {

    public AnimationState state = new AnimationState();
    protected boolean open;
    protected int age = 0;

    private String keyName = "";

    private int closeCountdown = 0;
    private int jammedTime = 0;
    private boolean blasted = false;

    public DoorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.open = state.get(Properties.OPEN);
        this.state.start(this.age);
        this.state.skip(10, 1);
    }

    public static <T extends DoorBlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T entity) {
        entity.age++;
    }

    public static <T extends DoorBlockEntity> void serverTick(World world, BlockPos pos, BlockState state, T entity) {
        /*
         * 这里读取方块实体的实时状态，而不是 ticker 传入的旧 BlockState。
         * 双门第一侧自动关门时会同时关闭第二侧；第二侧随后 tick 时必须看到已经关闭，不能再反向打开。
         */
        DoorApi.maintainGroupInvariants(entity);
        if (entity.isOpen() && !DoorApi.isBlasted(entity)) {
            entity.setCloseCountdown(entity.getCloseCountdown() - 1);
            if (entity.getCloseCountdown() <= 0) {
                DoorApi.setOpen(entity, false, false, false);
            }
        } else {
            entity.setCloseCountdown(0);
        }

        if (entity.isJammed()) {
            entity.setJammed(entity.getJammedTime() - 1);
        }
    }

    public void toggle(boolean silent) {
        DoorApi.toggle(this, silent);
    }

    /**
     * DoorApi 的单扇落地方法。只写明确目标状态，不查找邻门，也不播放声音。
     * 扩展玩法应调用 DoorApi 的门组方法，不应直接组合这个底层入口。
     */
    public boolean applyOpenState(boolean open, int closeCountdown) {
        if (this.world == null || this.world.isClient) {
            return false;
        }
        boolean changed = this.open != open || this.getCachedState().get(DoorPartBlock.OPEN) != open;
        this.open = open;
        this.closeCountdown = open ? Math.max(0, closeCountdown) : 0;
        if (changed) {
            this.world.addSyncedBlockEvent(this.pos, this.getCachedState().getBlock(), 1, open ? 1 : 0);
            this.toggleBlocks();
        }
        this.markDirty();
        return changed;
    }

    public void playToggleSound() {
        if (this.world == null) {
            return;
        }
        this.world.playSound(null, this.pos, WatheSounds.BLOCK_DOOR_TOGGLE, SoundCategory.BLOCKS, 1f, 1f);
    }

    protected abstract void toggleBlocks();

    public boolean isOpen() {
        return this.open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public float getYaw() {
        return 180 - this.getFacing().asRotation();
    }

    public Direction getFacing() {
        return this.getCachedState().get(Properties.HORIZONTAL_FACING);
    }

    public int getAge() {
        return this.age;
    }

    @Override
    public boolean onSyncedBlockEvent(int type, int data) {
        if (this.world != null && type == 1) {
            this.state.start(this.age);
            this.open = data != 0;
            return true;
        }
        return super.onSyncedBlockEvent(type, data);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        nbt.putBoolean("open", this.isOpen());
        nbt.putBoolean("blasted", this.isBlasted());
        nbt.putInt("closeCountdown", this.getCloseCountdown());
        nbt.putInt("jammedTime", this.getJammedTime());
        nbt.putString("keyName", this.getKeyName());
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        this.setOpen(nbt.getBoolean("open"));
        this.setBlasted(nbt.getBoolean("blasted"));
        this.setCloseCountdown(nbt.getInt("closeCountdown"));
        this.setJammed(nbt.getInt("jammedTime"));
        this.setKeyName(nbt.getString("keyName"));
    }

    public String getKeyName() {
        return this.keyName;
    }

    public void setKeyName(String string) {
        this.keyName = string;
    }

    public int getCloseCountdown() {
        return closeCountdown;
    }

    public void setCloseCountdown(int closeCountdown) {
        this.closeCountdown = closeCountdown;
    }

    public void setJammed(int time) {
        this.jammedTime = time;
    }

    /** 仅供 DoorApi、NBT 恢复和旧扩展兼容层使用；新玩法应调用 DoorApi。 */
    public void setJammedDirect(int time) {
        this.jammedTime = Math.max(0, time);
    }

    public void jam() {
        DoorApi.jamClosed(this, GameConstants.JAMMED_DOOR_TIME);
    }

    public void blast() {
        DoorApi.pryOpen(this);
    }

    public boolean isJammed() {
        return DoorApi.isJammed(this);
    }

    /** DoorApi 聚合门组状态时使用，避免 isJammed() 再次递归查询门组。 */
    public boolean isJammedDirect() {
        return this.jammedTime > 0;
    }

    public int getJammedTime() {
        return jammedTime;
    }

    public boolean isBlasted() {
        return DoorApi.isBlasted(this);
    }

    public void setBlasted(boolean blasted) {
        this.blasted = blasted;
    }

    /** DoorApi 聚合门组状态时使用，避免 isBlasted() 再次递归查询门组。 */
    public boolean isBlastedDirect() {
        return this.blasted;
    }

    /** 仅供 DoorApi、NBT 恢复和旧扩展兼容层使用；新玩法应调用 DoorApi。 */
    public void setBlastedDirect(boolean blasted) {
        this.blasted = blasted;
    }
}
