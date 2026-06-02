package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheProperties;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.List;

public class WorldBlackoutComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<WorldBlackoutComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("blackout"), WorldBlackoutComponent.class);
    private final World world;
    private final List<BlackoutDetails> blackouts = new ArrayList<>();
    private int ticks = 0;
    /**
     * 当前这一次停电的“全局时间线总时长”。
     *
     * <p>这里固定使用 {@link GameConstants#BLACKOUT_MAX_DURATION}，
     * 因为用户要求：
     * 1. {@link GameConstants#BLACKOUT_MIN_DURATION} 表示“开始恢复电力”的时刻；
     * 2. {@link GameConstants#BLACKOUT_MAX_DURATION} 表示“完全恢复电力”的时刻。
     *
     * <p>也就是说，这两个事件应该按“从停电开始经过了多久”来判定，
     * 而不是绑定到某一盏灯随机抽到的剩余时长。</p>
     */
    private int blackoutTotalTicks = 0;
    /**
     * 保证每轮停电只记录一次“开始恢复”事件。
     */
    private boolean recoveringEventSent = false;
    /**
     * 保证每轮停电只记录一次“完全恢复”事件。
     */
    private boolean restoredEventSent = false;

    public WorldBlackoutComponent(World world) {
        this.world = world;
    }

    public void reset() {
        for (BlackoutDetails detail : this.blackouts) detail.end(this.world);
        this.blackouts.clear();
        clearBlackoutTimelineState();
    }

    @Override
    public void serverTick() {
        for (int i = 0; i < this.blackouts.size(); i++) {
            BlackoutDetails detail = this.blackouts.get(i);
            detail.tick(this.world);
            if (detail.time <= 0) {
                detail.end(this.world);
                this.blackouts.remove(i);
                i--;
            }
        }

        if (this.world instanceof ServerWorld serverWorld) {
            /*
             * 回放时间线里的两条停电影响事件，应该对应：
             * 1. BLACKOUT_MIN_DURATION：电力开始恢复；
             * 2. BLACKOUT_MAX_DURATION：电力完全恢复。
             *
             * 这里不再看“剩余 5 tick / 1 tick”，而是看本轮停电从开始到现在已经过去了多久。
             * 由于当前实现的全局倒计时是在 serverTick 末尾递减，
             * 所以本 tick 对应的已过时间要按“包含当前 tick”来算。
             */
            int elapsedTicksInclusive = this.blackoutTotalTicks - this.ticks + 1;

            if (!this.recoveringEventSent && this.blackoutTotalTicks > 0 && elapsedTicksInclusive >= GameConstants.BLACKOUT_MIN_DURATION) {
                this.recoveringEventSent = true;
                GameRecordManager.recordGlobalEvent(serverWorld, Wathe.id("blackout_recovering"), null, null);
            }

            if (!this.restoredEventSent && this.blackoutTotalTicks > 0 && elapsedTicksInclusive >= GameConstants.BLACKOUT_MAX_DURATION) {
                this.restoredEventSent = true;
                GameRecordManager.recordGlobalEvent(serverWorld, Wathe.id("blackout_restored"), null, null);
            }
        }

        if (this.ticks > 0) {
            this.ticks--;
        } else if (this.blackouts.isEmpty()) {
            /*
             * 所有灯已经彻底恢复后，清理本轮停电的时间线状态，
             * 给下一次触发停电留出干净的计数环境。
             */
            clearBlackoutTimelineState();
        }
    }

    public boolean isBlackoutActive() {
        return this.ticks > 0;
    }

    public boolean triggerBlackout() {
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(world);

        Box area = areas.playArea;
        if (this.ticks > 0) return false;
        clearBlackoutTimelineState();
        /*
         * 全局回放时间线固定走 MAX_DURATION：
         * 20 秒开始恢复，35 秒完全恢复。
         * 单个灯源本身仍可以继续保留原版的随机恢复节奏。
         */
        this.ticks = GameConstants.BLACKOUT_MAX_DURATION;
        this.blackoutTotalTicks = GameConstants.BLACKOUT_MAX_DURATION;
        for (int x = (int) area.minX; x <= (int) area.maxX; x++) {
            for (int y = (int) area.minY; y <= (int) area.maxY; y++) {
                for (int z = (int) area.minZ; z <= (int) area.maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = this.world.getBlockState(pos);
                    if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) continue;
                    int duration = GameConstants.BLACKOUT_MIN_DURATION + this.world.random.nextInt(GameConstants.BLACKOUT_MAX_DURATION - GameConstants.BLACKOUT_MIN_DURATION);
                    BlackoutDetails detail = new BlackoutDetails(pos, duration, state.get(Properties.LIT));
                    detail.init(this.world);
                    this.blackouts.add(detail);
                }
            }
        }
        if (this.world instanceof ServerWorld serverWorld) for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(WatheSounds.AMBIENT_BLACKOUT), SoundCategory.PLAYERS, player.getX(), player.getY(), player.getZ(), 100f, 1f, player.getRandom().nextLong()));
        }
        return true;
    }

    /**
     * 清理本轮停电对应的全局时间线状态。
     *
     * <p>之所以抽成独立方法，是因为 reset / 新停电开始 / 完全恢复后
     * 都需要把这些运行时标记归零。</p>
     */
    private void clearBlackoutTimelineState() {
        this.ticks = 0;
        this.blackoutTotalTicks = 0;
        this.recoveringEventSent = false;
        this.restoredEventSent = false;
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        for (BlackoutDetails detail : this.blackouts) list.add(detail.writeToNbt());
        tag.put("blackouts", list);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.blackouts.clear();
        for (NbtElement element : tag.getList("blackouts", 10)) {
            BlackoutDetails detail = new BlackoutDetails((NbtCompound) element);
            detail.init(this.world);
            this.blackouts.add(detail);
        }
    }

    public static class BlackoutDetails {
        private final BlockPos pos;
        private final boolean original;
        private int time;

        public BlackoutDetails(BlockPos pos, int time, boolean original) {
            this.pos = pos;
            this.time = time;
            this.original = original;
        }

        public BlackoutDetails(@NotNull NbtCompound tag) {
            this.pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            this.time = tag.getInt("time");
            this.original = tag.getBoolean("original");
        }

        public void init(@NotNull World world) {
            BlockState state = world.getBlockState(this.pos);
            if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) return;
            world.setBlockState(this.pos, state.with(Properties.LIT, false).with(WatheProperties.ACTIVE, false));
            world.playSound(null, this.pos, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 1f);
        }

        public void end(@NotNull World world) {
            BlockState state = world.getBlockState(this.pos);
            if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) return;
            world.setBlockState(this.pos, state.with(Properties.LIT, this.original).with(WatheProperties.ACTIVE, true));
            world.playSound(null, this.pos, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 0.5f);
        }

        public void tick(World world) {
            if (this.time > 0) this.time--;
            if (this.time > 4) return;
            BlockState state = world.getBlockState(this.pos);
            if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) return;
            switch (this.time) {
                case 0 -> this.end(world);
                case 1, 3 -> {
                    world.setBlockState(this.pos, state.with(Properties.LIT, false));
                    world.playSound(null, this.pos, WatheSounds.BLOCK_BUTTON_TOGGLE_NO_POWER, SoundCategory.BLOCKS, 0.1f, 1f);
                }
                case 2, 5 -> {
                    world.setBlockState(this.pos, state.with(Properties.LIT, true));
                    world.playSound(null, this.pos, WatheSounds.BLOCK_BUTTON_TOGGLE_NO_POWER, SoundCategory.BLOCKS, 0.1f, 1f);
                }
            }
        }

        public NbtCompound writeToNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("x", this.pos.getX());
            tag.putInt("y", this.pos.getY());
            tag.putInt("z", this.pos.getZ());
            tag.putInt("time", this.time);
            tag.putBoolean("original", this.original);
            return tag;
        }
    }
}
