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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.RegistryByteBuf;
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
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WorldBlackoutComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<WorldBlackoutComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("blackout"), WorldBlackoutComponent.class);
    public static final int DEFAULT_OVERLAY_OPACITY_PERCENT = 80;
    /**
     * 停电只改变同一个灯方块的 LIT / ACTIVE 属性，不需要通知六个相邻方块重新计算形状。
     *
     * <p>NOTIFY_LISTENERS 仍会把方块变化发送给观察该区块的客户端，并保留亮度变化所需的
     * 光照检查；FORCE_STATE 则跳过邻居 prepare/updateNeighbors 链，避免相连霓虹灯传播 ACTIVE
     * 时把一次停电更新放大成额外的连锁方块更新。</p>
     */
    private static final int BLACKOUT_BLOCK_UPDATE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private final World world;
    /**
     * 服务端尚未恢复的灯集合。LinkedHashSet 既保留稳定的存档顺序，也让批次完成后的删除为 O(1)，
     * 避免旧 ArrayList 在每恢复一盏灯时搬移后续全部元素。
     */
    private final Set<BlackoutDetails> blackouts = new LinkedHashSet<>();
    /**
     * 按计划恢复时间排序的区块分段批次。服务端每 tick 只查看队首已到期批次，
     * 不再像旧实现一样遍历所有灯并给每盏灯单独递减倒计时。
     */
    private final ArrayDeque<RecoveryBatch> recoveryBatches = new ArrayDeque<>();
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
     * 恢复阶段下一次全局灯光恢复声还要等待的 tick 数。
     * 只在服务端使用，不参与客户端黑幕同步；每次播放后都会按 GameConstants 的最短/最长
     * 间隔重新随机，因而不会形成机械的固定节拍，也不会为每盏灯单独发送声音包。
     */
    private int recoverySoundTicksUntilNext = 0;
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
        this.recoveryBatches.clear();
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
        boolean hadBlackout = this.ticks > 0 || !this.blackouts.isEmpty();
        this.reset();
        if (hadBlackout && this.world instanceof ServerWorld serverWorld) {
            sendRecoveryCompletedSound(serverWorld);
        }
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
        } else {
            clearBlackoutTimelineState();
        }
    }

    @Override
    public void serverTick() {
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
                sendRecoveryStartedSound(serverWorld);
            }

            if (this.blackoutTotalTicks > 0 && elapsedTicksInclusive >= this.blackoutMinDurationTicks) {
                processRecoveryBatches(elapsedTicksInclusive);
                if (!this.blackouts.isEmpty() && !this.restoredEventSent) {
                    tickRecoverySound(serverWorld);
                }
            }

            /*
             * “完全恢复”必须以真实恢复队列清空为准。正常数量的灯仍会在 MAX_DURATION 当 tick
             * 恢复完毕；若极端地图触发了批次/耗时预算，尾部允许延后几个 tick，避免为了严格
             * 追赶截止时间重新制造服务器 TPS 和客户端区块重建尖峰。
             */
            if (!this.restoredEventSent
                    && this.blackoutTotalTicks > 0
                    && elapsedTicksInclusive >= this.blackoutMaxDurationTicks
                    && this.blackouts.isEmpty()) {
                this.restoredEventSent = true;
                GameRecordManager.recordGlobalEvent(serverWorld, Wathe.id("blackout_restored"), null, null);
                sendRecoveryCompletedSound(serverWorld);
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
        // 配置时间线结束后仍可能有受预算保护的少量尾部批次，必须等它们恢复完才能开始下一次停电。
        if (this.ticks > 0 || !this.blackouts.isEmpty()) return false;
        clearBlackoutTimelineState();
        this.recoveryBatches.clear();
        BlackoutDuration duration = this.world instanceof ServerWorld serverWorld
                ? BlackoutApi.resolveDuration(serverWorld, GameWorldComponent.KEY.get(serverWorld))
                : BlackoutDuration.of(GameConstants.BLACKOUT_MIN_DURATION, GameConstants.BLACKOUT_MAX_DURATION);
        /*
         * MIN_DURATION 是开始恢复的时间点，MAX_DURATION 是正常情况下的目标完成时间。
         * 灯不再各自随机倒计时，而是在扫描完成后按区块分段组成批次并均匀铺进这个窗口，
         * 从根源上避免随机结果把大量、空间上分散的灯挤到同一个 tick。
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
                    BlackoutDetails detail = new BlackoutDetails(pos, 0, state.get(Properties.LIT));
                    detail.init(this.world);
                    this.blackouts.add(detail);
                }
            }
        }
        createBalancedRecoverySchedule();
        this.sync();
        return true;
    }

    /**
     * 把全部待恢复灯按 ChunkSection 分组，再把分组均匀安排到恢复窗口。
     *
     * <p>客户端渲染网格和 Minecraft 的多方块更新包都以区块分段为重要边界。同一分段的灯
     * 集中恢复，可以显著减少同一片网格在连续多个 tick 中反复重建。超过单批灯数上限的
     * 超高密度分段会拆成数批，防止某一个分段本身形成尖峰。</p>
     */
    private void createBalancedRecoverySchedule() {
        this.recoveryBatches.clear();
        Map<Long, List<BlackoutDetails>> lightsBySection = new HashMap<>();
        for (BlackoutDetails detail : this.blackouts) {
            lightsBySection.computeIfAbsent(detail.sectionKey(), ignored -> new ArrayList<>()).add(detail);
        }

        List<RecoveryBatch> batches = new ArrayList<>();
        for (Map.Entry<Long, List<BlackoutDetails>> entry : lightsBySection.entrySet()) {
            List<BlackoutDetails> sectionLights = entry.getValue();
            for (int start = 0; start < sectionLights.size(); start += GameConstants.BLACKOUT_RECOVERY_MAX_LIGHTS_PER_BATCH) {
                int end = Math.min(sectionLights.size(), start + GameConstants.BLACKOUT_RECOVERY_MAX_LIGHTS_PER_BATCH);
                batches.add(new RecoveryBatch(0, entry.getKey(), new ArrayList<>(sectionLights.subList(start, end))));
            }
        }

        // 只随机区块批次的先后，不再给每盏灯独立随机时间，从而同时保留自然变化和稳定负载。
        for (int index = batches.size() - 1; index > 0; index--) {
            int swapIndex = this.world.random.nextInt(index + 1);
            RecoveryBatch temporary = batches.get(index);
            batches.set(index, batches.get(swapIndex));
            batches.set(swapIndex, temporary);
        }

        int recoverySpan = Math.max(1, this.blackoutMaxDurationTicks - this.blackoutMinDurationTicks);
        for (int index = 0; index < batches.size(); index++) {
            int restoreAtElapsedTick = batches.size() == 1
                    ? this.blackoutMaxDurationTicks
                    : this.blackoutMinDurationTicks + (int) ((long) index * recoverySpan / (batches.size() - 1));
            RecoveryBatch batch = batches.get(index).scheduledAt(restoreAtElapsedTick);
            for (BlackoutDetails detail : batch.lights) {
                detail.restoreAtElapsedTick = restoreAtElapsedTick;
            }
            this.recoveryBatches.addLast(batch);
        }
    }

    /**
     * 处理本 tick 已到期的恢复批次。
     *
     * <p>批次数上限约束客户端每 tick 需要重建的区块分段数量，纳秒预算约束服务端主线程。
     * 两个预算都不会拆开一个已经开始的批次；到期但没来得及处理的批次留在队首，后续 tick
     * 继续恢复。这样在恢复间隔很短、灯异常多时只延长尾部，而不会出现集中卡顿。</p>
     */
    private void processRecoveryBatches(int elapsedTicksInclusive) {
        long startedAt = System.nanoTime();
        int processedBatches = 0;
        while (!this.recoveryBatches.isEmpty()) {
            RecoveryBatch batch = this.recoveryBatches.peekFirst();
            if (batch.restoreAtElapsedTick > elapsedTicksInclusive) {
                return;
            }
            if (processedBatches >= GameConstants.BLACKOUT_RECOVERY_MAX_BATCHES_PER_TICK) {
                return;
            }
            if (processedBatches > 0
                    && System.nanoTime() - startedAt >= GameConstants.BLACKOUT_RECOVERY_MAX_NANOS_PER_TICK) {
                return;
            }

            this.recoveryBatches.removeFirst();
            for (BlackoutDetails detail : batch.lights) {
                detail.end(this.world);
                this.blackouts.remove(detail);
            }
            processedBatches++;
        }
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
     * 电力开始恢复时播放一次全局灯光恢复声。
     *
     * <p>这里刻意复用旧版逐灯真正恢复时的 {@link WatheSounds#BLOCK_LIGHT_TOGGLE}，
     * 而不是旧版闪烁阶段的低音量 {@code BLOCK_BUTTON_TOGGLE_NO_POWER}；这样玩家听到的
     * 声音与“灯已经恢复供电”的原始反馈一致，同时仍然只向每位玩家发送一个全局声音包。</p>
     */
    private void sendRecoveryStartedSound(@NotNull ServerWorld serverWorld) {
        playGlobalSoundToPlayers(serverWorld, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 0.5f);
        this.recoverySoundTicksUntilNext = nextRecoverySoundInterval();
    }

    /**
     * 最后一个恢复批次完成后只播放一次全局开灯声。
     */
    private void sendRecoveryCompletedSound(@NotNull ServerWorld serverWorld) {
        // 完成声也保持旧版逐灯最终恢复时的音量和低音调，避免最后一次反馈突然变调。
        playGlobalSoundToPlayers(serverWorld, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 0.5f);
        this.recoverySoundTicksUntilNext = 0;
    }

    /**
     * 恢复阶段的全局循环提示声。
     *
     * <p>这是对旧版逐灯恢复音效的低成本还原：声音仍然会间歇性出现，让玩家明确知道
     * 电力正在逐步回来，但所有玩家共享同一条全局节奏，不再随着灯数量生成海量声音包。</p>
     */
    private void tickRecoverySound(@NotNull ServerWorld serverWorld) {
        if (this.recoverySoundTicksUntilNext > 0) {
            this.recoverySoundTicksUntilNext--;
        }
        if (this.recoverySoundTicksUntilNext <= 0) {
            // 使用旧版每盏灯最终恢复时的音效、音量和音调，保留原有听感但不恢复逐灯声音包。
            playGlobalSoundToPlayers(serverWorld, WatheSounds.BLOCK_LIGHT_TOGGLE, SoundCategory.BLOCKS, 0.5f, 0.5f);
            this.recoverySoundTicksUntilNext = nextRecoverySoundInterval();
        }
    }

    /**
     * 在配置的最短/最长间隔之间取一个随机值。非法或反向配置也会被钳制，避免调试时出现
     * nextInt(0) 或负数间隔导致每 tick 播放声音。
     */
    private int nextRecoverySoundInterval() {
        int min = Math.max(1, Math.min(GameConstants.BLACKOUT_RECOVERY_SOUND_MIN_INTERVAL,
                GameConstants.BLACKOUT_RECOVERY_SOUND_MAX_INTERVAL));
        int max = Math.max(min, Math.max(GameConstants.BLACKOUT_RECOVERY_SOUND_MIN_INTERVAL,
                GameConstants.BLACKOUT_RECOVERY_SOUND_MAX_INTERVAL));
        return min == max ? min : min + this.world.random.nextInt(max - min + 1);
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
        this.recoverySoundTicksUntilNext = 0;
    }

    /**
     * CCA 默认会把 writeToNbt 的完整内容同步给每位客户端，其中包含所有灯坐标。
     * 客户端黑幕只需要时间线和调试配置，因此这里使用紧凑字段同步，避免大地图触发停电时
     * 为每个玩家编码、发送并解析成千上万个灯坐标。完整灯列表仍由 writeToNbt 写入世界存档。
     */
    @Override
    public void writeSyncPacket(@NotNull RegistryByteBuf buf, @NotNull ServerPlayerEntity recipient) {
        buf.writeVarInt(Math.max(0, this.ticks));
        buf.writeVarInt(Math.max(0, this.blackoutTotalTicks));
        buf.writeVarInt(Math.max(1, this.blackoutMinDurationTicks));
        buf.writeVarInt(Math.max(this.blackoutMinDurationTicks + 1, this.blackoutMaxDurationTicks));
        buf.writeVarInt(MathHelper.clamp(this.overlayOpacityPercent, 0, 100));
        buf.writeBoolean(this.potionEffectsEnabled);
    }

    /**
     * 客户端只接收黑幕渲染所需状态；真实灯恢复完全由服务端方块更新包驱动。
     */
    @Override
    public void applySyncPacket(@NotNull RegistryByteBuf buf) {
        this.ticks = Math.max(0, buf.readVarInt());
        this.blackoutTotalTicks = Math.max(0, buf.readVarInt());
        this.blackoutMinDurationTicks = Math.max(1, buf.readVarInt());
        this.blackoutMaxDurationTicks = Math.max(this.blackoutMinDurationTicks + 1, buf.readVarInt());
        this.overlayOpacityPercent = MathHelper.clamp(buf.readVarInt(), 0, 100);
        this.potionEffectsEnabled = buf.readBoolean();
        this.blackouts.clear();
        this.recoveryBatches.clear();
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
        this.recoveryBatches.clear();
        this.ticks = tag.contains("ticks") ? Math.max(0, tag.getInt("ticks")) : 0;
        this.blackoutTotalTicks = tag.contains("blackoutTotalTicks") ? Math.max(0, tag.getInt("blackoutTotalTicks")) : this.ticks;
        this.blackoutMinDurationTicks = tag.contains("blackoutMinDurationTicks") ? Math.max(1, tag.getInt("blackoutMinDurationTicks")) : GameConstants.BLACKOUT_MIN_DURATION;
        this.blackoutMaxDurationTicks = tag.contains("blackoutMaxDurationTicks") ? Math.max(this.blackoutMinDurationTicks + 1, tag.getInt("blackoutMaxDurationTicks")) : Math.max(this.blackoutMinDurationTicks + 1, GameConstants.BLACKOUT_MAX_DURATION);
        int elapsedTicks = Math.max(0, this.blackoutTotalTicks - this.ticks);
        for (NbtElement element : tag.getList("blackouts", 10)) {
            BlackoutDetails detail = new BlackoutDetails((NbtCompound) element, elapsedTicks);
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
        if (!this.world.isClient) {
            rebuildRecoveryScheduleFromSavedDetails();
        }
        this.recoveringEventSent = tag.getBoolean("recoveringEventSent");
        this.restoredEventSent = tag.getBoolean("restoredEventSent");
        this.overlayOpacityPercent = tag.contains("overlayOpacityPercent") ? MathHelper.clamp(tag.getInt("overlayOpacityPercent"), 0, 100) : DEFAULT_OVERLAY_OPACITY_PERCENT;
        this.potionEffectsEnabled = !tag.contains("potionEffectsEnabled") || tag.getBoolean("potionEffectsEnabled");
    }

    /**
     * 世界读档时根据每盏灯保存的绝对恢复时间重新建立队列。
     * 同一恢复 tick、同一区块分段的灯会重新合并，保证重启服务器后仍使用批处理路径。
     */
    private void rebuildRecoveryScheduleFromSavedDetails() {
        Map<SavedBatchKey, List<BlackoutDetails>> grouped = new HashMap<>();
        for (BlackoutDetails detail : this.blackouts) {
            SavedBatchKey key = new SavedBatchKey(detail.restoreAtElapsedTick, detail.sectionKey());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(detail);
        }

        List<RecoveryBatch> rebuilt = new ArrayList<>();
        for (Map.Entry<SavedBatchKey, List<BlackoutDetails>> entry : grouped.entrySet()) {
            List<BlackoutDetails> lights = entry.getValue();
            for (int start = 0; start < lights.size(); start += GameConstants.BLACKOUT_RECOVERY_MAX_LIGHTS_PER_BATCH) {
                int end = Math.min(lights.size(), start + GameConstants.BLACKOUT_RECOVERY_MAX_LIGHTS_PER_BATCH);
                rebuilt.add(new RecoveryBatch(
                        entry.getKey().restoreAtElapsedTick,
                        entry.getKey().sectionKey,
                        new ArrayList<>(lights.subList(start, end))
                ));
            }
        }
        rebuilt.sort(Comparator.comparingInt(batch -> batch.restoreAtElapsedTick));
        this.recoveryBatches.addAll(rebuilt);
    }

    public static class BlackoutDetails {
        private final BlockPos pos;
        private final boolean original;
        private int restoreAtElapsedTick;

        public BlackoutDetails(BlockPos pos, int restoreAtElapsedTick, boolean original) {
            this.pos = pos;
            this.restoreAtElapsedTick = restoreAtElapsedTick;
            this.original = original;
        }

        public BlackoutDetails(@NotNull NbtCompound tag, int currentElapsedTicks) {
            this.pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            /*
             * 兼容优化前的存档：旧 time 表示“从读档时还剩多少 tick”，新字段表示从本轮停电
             * 开始算起的绝对恢复 tick。迁移后队列无需再逐灯递减 time。
             */
            this.restoreAtElapsedTick = tag.contains("restoreAtElapsedTick")
                    ? Math.max(0, tag.getInt("restoreAtElapsedTick"))
                    : currentElapsedTicks + Math.max(0, tag.getInt("time"));
            this.original = tag.getBoolean("original");
        }

        private long sectionKey() {
            return ChunkSectionPos.from(this.pos).asLong();
        }

        public void init(@NotNull World world) {
            BlockState state = world.getBlockState(this.pos);
            if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) return;
            /*
             * 批量停电时，单盏灯只负责进入断电状态，不再各自播放关灯声。
             * 全局关灯声已经在 triggerBlackout() 开始阶段统一播放一次；
             * 这样可以避免灯很多的地图在同一 tick 发出大量灯音效包，挤掉停电环境音。
             */
            world.setBlockState(this.pos, state.with(Properties.LIT, false).with(WatheProperties.ACTIVE, false), BLACKOUT_BLOCK_UPDATE_FLAGS);
        }

        public void end(@NotNull World world) {
            BlockState state = world.getBlockState(this.pos);
            if (!state.contains(Properties.LIT) || !state.contains(WatheProperties.ACTIVE)) return;
            // 每盏灯只写入一次最终状态；恢复阶段的声音由世界组件在开始和完成时各全局播放一次。
            world.setBlockState(this.pos, state.with(Properties.LIT, this.original).with(WatheProperties.ACTIVE, true), BLACKOUT_BLOCK_UPDATE_FLAGS);
        }

        public NbtCompound writeToNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("x", this.pos.getX());
            tag.putInt("y", this.pos.getY());
            tag.putInt("z", this.pos.getZ());
            tag.putInt("restoreAtElapsedTick", this.restoreAtElapsedTick);
            tag.putBoolean("original", this.original);
            return tag;
        }
    }

    private static final class RecoveryBatch {
        private final int restoreAtElapsedTick;
        private final long sectionKey;
        private final List<BlackoutDetails> lights;

        private RecoveryBatch(int restoreAtElapsedTick, long sectionKey, List<BlackoutDetails> lights) {
            this.restoreAtElapsedTick = restoreAtElapsedTick;
            this.sectionKey = sectionKey;
            this.lights = lights;
        }

        private RecoveryBatch scheduledAt(int restoreAtElapsedTick) {
            return new RecoveryBatch(restoreAtElapsedTick, this.sectionKey, this.lights);
        }
    }

    private record SavedBatchKey(int restoreAtElapsedTick, long sectionKey) {
    }
}
