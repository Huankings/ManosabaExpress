package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.blackout.BlackoutApi;
import dev.doctor4t.wathe.api.blackout.BlackoutDuration;
import dev.doctor4t.wathe.api.blackout.BlackoutEffectContext;
import dev.doctor4t.wathe.api.blackout.BlackoutEffectResult;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
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
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.List;

public class WorldBlackoutComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<WorldBlackoutComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("blackout"), WorldBlackoutComponent.class);
    public static final int DEFAULT_OVERLAY_OPACITY_PERCENT = 80;
    private final World world;
    private final List<BlackoutDetails> blackouts = new ArrayList<>();
    private int ticks = 0;
    /**
     * 当前这一次停电的“全局时间线总时长”。
     *
     * <p>默认使用 {@link GameConstants#BLACKOUT_MAX_DURATION}，
     * 并允许扩展通过 {@link BlackoutApi#registerDurationModifier} 覆盖，因为用户要求：
     * 1. {@link GameConstants#BLACKOUT_MIN_DURATION} 表示“开始恢复电力”的时刻；
     * 2. {@link GameConstants#BLACKOUT_MAX_DURATION} 表示“完全恢复电力”的时刻。
     *
     * <p>也就是说，这两个事件应该按“从停电开始经过了多久”来判定，
     * 而不是绑定到某一盏灯随机抽到的剩余时长。</p>
     */
    private int blackoutTotalTicks = 0;
    /**
     * 当前这轮停电的“开始恢复”时间点。
     *
     * <p>默认来自 {@link GameConstants#BLACKOUT_MIN_DURATION}，但扩展模组可以通过
     * {@link BlackoutApi#registerDurationModifier} 修改。本字段会同步给客户端黑幕 HUD，
     * 让黑幕从正确的恢复时间开始淡出。</p>
     */
    private int blackoutMinDurationTicks = GameConstants.BLACKOUT_MIN_DURATION;
    /**
     * 当前这轮停电的“完全恢复”时间点。
     */
    private int blackoutMaxDurationTicks = GameConstants.BLACKOUT_MAX_DURATION;
    /**
     * 保证每轮停电只记录一次“开始恢复”事件。
     */
    private boolean recoveringEventSent = false;
    /**
     * 保证每轮停电只记录一次“完全恢复”事件。
     */
    private boolean restoredEventSent = false;
    /**
     * 客户端黑幕强度调试值，0 表示关闭黑幕，100 表示完全不透明。
     *
     * <p>这个值放在世界组件里并同步给客户端，而不是只存在服务端命令类，
     * 这样管理员游戏中调整后所有玩家都能立即看到同一效果。</p>
     */
    private int overlayOpacityPercent = DEFAULT_OVERLAY_OPACITY_PERCENT;
    /**
     * 是否启用停电期间 Wathe 统一分配的夜视/失明效果。
     *
     * <p>该开关只影响 Wathe 停电系统自己发放的短时药水；
     * 职业技能和物品给出的独立药水效果不受这个调试开关控制。</p>
     */
    private boolean potionEffectsEnabled = true;

    public WorldBlackoutComponent(World world) {
        this.world = world;
    }

    public void sync() {
        if (!this.world.isClient) {
            KEY.sync(this.world);
        }
    }

    public void reset() {
        for (BlackoutDetails detail : this.blackouts) detail.end(this.world);
        this.blackouts.clear();
        clearBlackoutTimelineState();
        if (this.world instanceof ServerWorld serverWorld) {
            clearAllBlackoutEffects(serverWorld);
        }
        this.sync();
    }

    /**
     * 公开的“恢复供电”入口。
     *
     * <p>语义上等同于结束当前停电：恢复灯光、清空倒计时、清理 Wathe 停电药水并同步客户端。
     * 扩展职业（例如工程师电力恢复系统）不再需要 mixin 私有 ticks 字段。</p>
     */
    public void restorePower() {
        this.reset();
    }

    @Override
    public void clientTick() {
        /*
         * 客户端只本地推进用于 HUD 的全局倒计时。
         * 服务端仍是权威状态：触发、恢复、结束游戏都会重新同步 ticks，
         * 因此不会再出现 kinssaba 旧实现那种“只靠音效计时导致跨局残留”的黑幕。
         */
        if (this.ticks > 0) {
            this.ticks--;
        } else if (this.blackouts.isEmpty()) {
            clearBlackoutTimelineState();
        }
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
            if (this.ticks > 0) {
                applyBlackoutEffects(serverWorld);
            }

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

            if (!this.recoveringEventSent && this.blackoutTotalTicks > 0 && elapsedTicksInclusive >= this.blackoutMinDurationTicks) {
                this.recoveringEventSent = true;
                GameRecordManager.recordGlobalEvent(serverWorld, Wathe.id("blackout_recovering"), null, null);
            }

            if (!this.restoredEventSent && this.blackoutTotalTicks > 0 && elapsedTicksInclusive >= this.blackoutMaxDurationTicks) {
                this.restoredEventSent = true;
                GameRecordManager.recordGlobalEvent(serverWorld, Wathe.id("blackout_restored"), null, null);
            }
        }

        if (this.ticks > 0) {
            this.ticks--;
            if (this.ticks == 0) {
                if (this.world instanceof ServerWorld serverWorld) {
                    releaseAllBlackoutEffects(serverWorld);
                }
                this.sync();
            }
        } else if (this.blackouts.isEmpty()) {
            /*
             * 所有灯已经彻底恢复后，清理本轮停电的时间线状态，
             * 给下一次触发停电留出干净的计数环境。
             */
            if (this.blackoutTotalTicks > 0 || this.recoveringEventSent || this.restoredEventSent) {
                clearBlackoutTimelineState();
                this.sync();
            }
        }
    }

    public boolean isBlackoutActive() {
        return this.ticks > 0;
    }

    public int getRemainingTicks() {
        return Math.max(0, this.ticks);
    }

    public int getTotalTicks() {
        return Math.max(0, this.blackoutTotalTicks);
    }

    public int getElapsedTicks() {
        return Math.max(0, this.getTotalTicks() - this.getRemainingTicks());
    }

    public int getMinDurationTicks() {
        return Math.max(1, this.blackoutMinDurationTicks);
    }

    public int getMaxDurationTicks() {
        return Math.max(this.getMinDurationTicks() + 1, this.blackoutMaxDurationTicks);
    }

    public int getOverlayOpacityPercent() {
        return this.overlayOpacityPercent;
    }

    public void setOverlayOpacityPercent(int overlayOpacityPercent) {
        this.overlayOpacityPercent = MathHelper.clamp(overlayOpacityPercent, 0, 100);
        this.sync();
    }

    public boolean arePotionEffectsEnabled() {
        return this.potionEffectsEnabled;
    }

    public void setPotionEffectsEnabled(boolean potionEffectsEnabled) {
        this.potionEffectsEnabled = potionEffectsEnabled;
        if (!potionEffectsEnabled && this.world instanceof ServerWorld serverWorld) {
            clearAllBlackoutEffects(serverWorld);
        }
        this.sync();
    }

    public boolean triggerBlackout() {
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(world);

        Box area = areas.playArea;
        if (this.ticks > 0) return false;
        clearBlackoutTimelineState();
        BlackoutDuration duration = this.world instanceof ServerWorld serverWorld
                ? BlackoutApi.resolveDuration(serverWorld, GameWorldComponent.KEY.get(serverWorld))
                : BlackoutDuration.of(GameConstants.BLACKOUT_MIN_DURATION, GameConstants.BLACKOUT_MAX_DURATION);
        /*
         * 全局回放时间线固定走 MAX_DURATION：
         * 20 秒开始恢复，35 秒完全恢复。
         * 单个灯源本身仍可以继续保留原版的随机恢复节奏。
         */
        this.ticks = duration.maxTicks();
        this.blackoutTotalTicks = duration.maxTicks();
        this.blackoutMinDurationTicks = duration.minTicks();
        this.blackoutMaxDurationTicks = duration.maxTicks();
        if (this.world instanceof ServerWorld serverWorld) {
            sendBlackoutStartSounds(serverWorld);
        }
        for (int x = (int) area.minX; x <= (int) area.maxX; x++) {
            for (int y = (int) area.minY; y <= (int) area.maxY; y++) {
                for (int z = (int) area.minZ; z <= (int) area.maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = this.world.getBlockState(pos);
                    if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) continue;
                    int lightDuration = this.blackoutMinDurationTicks + this.world.random.nextInt(Math.max(1, this.blackoutMaxDurationTicks - this.blackoutMinDurationTicks));
                    BlackoutDetails detail = new BlackoutDetails(pos, lightDuration, state.get(Properties.LIT));
                    detail.init(this.world);
                    this.blackouts.add(detail);
                }
            }
        }
        this.sync();
        return true;
    }

    /**
     * 播放停电开始时的全局声音反馈。
     *
     * <p>这里故意在扫描和关闭大量灯光之前发送声音包：
     * 停电环境音是玩家感知“停电已经成功触发”的核心反馈，如果排在批量
     * {@code setBlockState} 和灯光音效之后，地图灯很多时客户端可能先收到大量方块更新，
     * 导致环境音延迟甚至被声音系统挤掉。</p>
     *
     * <p>关灯声只全局播放一次，不再给每盏灯各播一次。这样既保留“全车灯灭”的听觉反馈，
     * 又避免几百/几千盏灯在同一个 tick 里制造海量声音包，影响真正重要的停电环境音。</p>
     */
    private void sendBlackoutStartSounds(@NotNull ServerWorld serverWorld) {
        playGlobalSoundToPlayers(serverWorld, WatheSounds.AMBIENT_BLACKOUT, SoundCategory.PLAYERS, 100f, 1f);
        playGlobalSoundToPlayers(serverWorld, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 1f);
    }

    /**
     * 以每个玩家自己的位置播放一次声音，让所有在线玩家都稳定听见。
     *
     * <p>这里不使用某个固定方块坐标广播，是因为不同地图尺寸和玩家距离会影响普通方块音效
     * 的衰减。把声音包发到玩家当前位置，可以表达“全局事件”的语义，同时仍然尊重对应的
     * Minecraft 声音分类音量设置。</p>
     */
    private static void playGlobalSoundToPlayers(
            @NotNull ServerWorld serverWorld,
            @NotNull SoundEvent sound,
            @NotNull SoundCategory category,
            float volume,
            float pitch
    ) {
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                    Registries.SOUND_EVENT.getEntry(sound),
                    category,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    volume,
                    pitch,
                    player.getRandom().nextLong()
            ));
        }
    }

    private void applyBlackoutEffects(@NotNull ServerWorld serverWorld) {
        if (!this.potionEffectsEnabled) {
            clearAllBlackoutEffects(serverWorld);
            return;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(serverWorld);
        if (gameWorld.getGameStatus() != GameWorldComponent.GameStatus.ACTIVE) {
            clearAllBlackoutEffects(serverWorld);
            return;
        }

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            PlayerBlackoutEffectComponent effectComponent = PlayerBlackoutEffectComponent.KEY.get(player);
            if (!GameFunctions.isPlayerAliveAndSurvival(player)) {
                effectComponent.clearOwnedEffect();
                continue;
            }

            Role role = gameWorld.getRole(player);
            BlackoutEffectResult result = BlackoutApi.resolveEffect(new BlackoutEffectContext(serverWorld, gameWorld, player, role));
            effectComponent.applyResolvedEffect(result);
        }
    }

    private void clearAllBlackoutEffects(@NotNull ServerWorld serverWorld) {
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            PlayerBlackoutEffectComponent.KEY.get(player).clearOwnedEffect();
        }
    }

    /**
     * 自然停电结束时只停止 Wathe 对短时药水的刷新，不立刻移除玩家身上的真实药水。
     *
     * <p>失明/夜视如果被 removeStatusEffect 直接删除，客户端画面会从黑暗瞬间跳回正常；
     * 让最后一次 60 tick 短效果自然倒计时结束，则会走原版药水自己的视觉过渡。
     * reset、restore、关调试开关、停局和玩家重置仍然调用 clearAllBlackoutEffects，
     * 这些强制路径需要立即清掉残留，避免跨局或调试残留。</p>
     */
    private void releaseAllBlackoutEffects(@NotNull ServerWorld serverWorld) {
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            PlayerBlackoutEffectComponent.KEY.get(player).releaseOwnedEffectToExpireNaturally();
        }
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
        this.blackoutMinDurationTicks = GameConstants.BLACKOUT_MIN_DURATION;
        this.blackoutMaxDurationTicks = GameConstants.BLACKOUT_MAX_DURATION;
        this.recoveringEventSent = false;
        this.restoredEventSent = false;
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        for (BlackoutDetails detail : this.blackouts) list.add(detail.writeToNbt());
        tag.put("blackouts", list);
        tag.putInt("ticks", this.ticks);
        tag.putInt("blackoutTotalTicks", this.blackoutTotalTicks);
        tag.putInt("blackoutMinDurationTicks", this.blackoutMinDurationTicks);
        tag.putInt("blackoutMaxDurationTicks", this.blackoutMaxDurationTicks);
        tag.putBoolean("recoveringEventSent", this.recoveringEventSent);
        tag.putBoolean("restoredEventSent", this.restoredEventSent);
        tag.putInt("overlayOpacityPercent", this.overlayOpacityPercent);
        tag.putBoolean("potionEffectsEnabled", this.potionEffectsEnabled);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        if (!this.world.isClient) {
            for (BlackoutDetails detail : this.blackouts) {
                detail.end(this.world);
            }
        }
        this.blackouts.clear();
        for (NbtElement element : tag.getList("blackouts", 10)) {
            BlackoutDetails detail = new BlackoutDetails((NbtCompound) element);
            /*
             * 客户端收到世界组件同步时只需要倒计时和配置来渲染 HUD。
             * 真实灯光状态由服务端方块更新同步，不在客户端 readNbt 里再次 init，
             * 避免调试命令同步组件时反复播放一批灯光开关声音。
             */
            if (!this.world.isClient) {
                detail.init(this.world);
            }
            this.blackouts.add(detail);
        }
        this.ticks = tag.contains("ticks") ? Math.max(0, tag.getInt("ticks")) : 0;
        this.blackoutTotalTicks = tag.contains("blackoutTotalTicks") ? Math.max(0, tag.getInt("blackoutTotalTicks")) : this.ticks;
        this.blackoutMinDurationTicks = tag.contains("blackoutMinDurationTicks") ? Math.max(1, tag.getInt("blackoutMinDurationTicks")) : GameConstants.BLACKOUT_MIN_DURATION;
        this.blackoutMaxDurationTicks = tag.contains("blackoutMaxDurationTicks") ? Math.max(this.blackoutMinDurationTicks + 1, tag.getInt("blackoutMaxDurationTicks")) : Math.max(this.blackoutMinDurationTicks + 1, GameConstants.BLACKOUT_MAX_DURATION);
        this.recoveringEventSent = tag.getBoolean("recoveringEventSent");
        this.restoredEventSent = tag.getBoolean("restoredEventSent");
        this.overlayOpacityPercent = tag.contains("overlayOpacityPercent") ? MathHelper.clamp(tag.getInt("overlayOpacityPercent"), 0, 100) : DEFAULT_OVERLAY_OPACITY_PERCENT;
        this.potionEffectsEnabled = !tag.contains("potionEffectsEnabled") || tag.getBoolean("potionEffectsEnabled");
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
            /*
             * 批量停电时，单盏灯只负责进入断电状态，不再各自播放关灯声。
             * 全局关灯声已经在 triggerBlackout() 开始阶段统一播放一次；
             * 这样可以避免灯很多的地图在同一 tick 发出大量灯音效包，挤掉停电环境音。
             */
            world.setBlockState(this.pos, state.with(Properties.LIT, false).with(WatheProperties.ACTIVE, false));
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
