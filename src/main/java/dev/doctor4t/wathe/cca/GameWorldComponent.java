package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.*;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.game.MapResetTask;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;



public class GameWorldComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
// 1. 变量定义
private int fixedKillerCount = -1;
    // 2. Getter/Setter
public void setFixedKillerCount(int count) { this.fixedKillerCount = count; }
public int getFixedKillerCount() { return this.fixedKillerCount; }

    public static final ComponentKey<GameWorldComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("game"), GameWorldComponent.class);
    private final World world;

    private boolean lockedToSupporters = false;
    private boolean enableWeights = false;
    /**
     * 心情死亡机制开关。
     * 默认开启；关闭后心情归零不会死亡，同时客户端也不会显示崩溃预警。
     */
    private boolean moodEffectDeathEnabled = true;
    /**
     * 控制“正在进行中的 Wathe 对局”里，存活玩家是否允许跳跃。
     *
     * <p>这里的“存活玩家”沿用 Wathe 现有定义：
     * 只要玩家不是 spectator、也不是 creative，就仍然算局内存活。
     *
     * <p>默认值为 true，
     * 这样切换地图时不需要再手动改源码去放开 jumpKey；
     * 真正想禁跳时，直接用 /wathe:allowjump false 即可动态关闭。
     */
    private boolean allowAlivePlayersJump = true;
    /**
     * 控制“正在进行中的 Wathe 对局”里，存活玩家之间是否启用实体碰撞体积。
     *
     * <p>默认值为 true，
     * 也就是保持当前玩法：局内仍然存活的玩家彼此不能直接穿过。
     *
     * <p>关闭后会恢复原版 {@code Entity#collidesWith} 的返回结果，
     * 不再由 Wathe 强制把玩家当作“实体墙”来阻挡彼此。
     */
    private boolean alivePlayersCollisionEnabled = true;

    public void setWeightsEnabled(boolean enabled) {
        this.enableWeights = enabled;
    }

    public boolean areWeightsEnabled() {
        return enableWeights;
    }

    public boolean isMoodEffectDeathEnabled() {
        return moodEffectDeathEnabled;
    }

    /**
     * 动态切换心情死亡机制。
     * 该值会立刻同步到客户端，方便游戏中直接调试。
     */
    public void setMoodEffectDeathEnabled(boolean moodEffectDeathEnabled) {
        this.moodEffectDeathEnabled = moodEffectDeathEnabled;
        this.sync();
    }

    /**
     * 返回“局内存活玩家是否允许跳跃”的当前配置。
     *
     * <p>该值只应在“对局正在运行中”时参与客户端按键限制判断；
     * 非对局状态下不应借此限制玩家在大厅、切图或调试时的正常跳跃。
     */
    public boolean isAlivePlayerJumpAllowed() {
        return allowAlivePlayersJump;
    }

    /**
     * 动态切换“局内存活玩家是否允许跳跃”。
     *
     * <p>因为客户端跳跃拦截逻辑要实时读取这个值，
     * 所以每次修改后都立刻同步，确保正在游戏中的玩家马上生效。
     */
    public void setAlivePlayerJumpAllowed(boolean allowAlivePlayersJump) {
        this.allowAlivePlayersJump = allowAlivePlayersJump;
        this.sync();
    }

    /**
     * 返回“局内存活玩家是否启用碰撞体积”的当前配置。
     *
     * <p>该值只用于 Wathe 额外注入的玩家碰撞逻辑；
     * 当它为 false 时，系统会退回原版实体碰撞判断，而不是继续强制拦住玩家。
     */
    public boolean isAlivePlayerCollisionEnabled() {
        return alivePlayersCollisionEnabled;
    }

    /**
     * 动态切换“局内存活玩家是否启用碰撞体积”。
     *
     * <p>由于碰撞判断在服务端与客户端都会参与移动/预测，
     * 所以这里和跳跃开关一样，修改后要立即同步，确保当前在线玩家立刻生效。
     */
    public void setAlivePlayerCollisionEnabled(boolean alivePlayersCollisionEnabled) {
        this.alivePlayersCollisionEnabled = alivePlayersCollisionEnabled;
        this.sync();
    }

    public enum GameStatus {
        INACTIVE, STARTING, ACTIVE, STOPPING
    }

    private GameMode gameMode = WatheGameModes.MURDER;
    private MapEffect mapEffect = WatheMapEffects.GENERIC;

    private boolean bound = true;

    private GameStatus gameStatus = GameStatus.INACTIVE;
    private int fade = 0;

    private final HashMap<UUID, Role> roles = new HashMap<>();

    private int ticksUntilNextResetAttempt = -1;

    /**
     * 控制新开局时是否启用渐进式地图重置。
     * 为 false 时仍走原版的一次性重置逻辑。
     */
    private boolean gradualResetEnabled = true;

    /**
     * 当前正在运行的渐进式重置任务。
     * 该字段只在运行期使用，不写入 NBT。
     */
    private @Nullable MapResetTask activeResetTask = null;

    /**
     * 记录下一次 {@link dev.doctor4t.wathe.game.GameFunctions#initializeGame(ServerWorld)}
     * 是否需要跳过原版的一次性排队重置。
     * 这样即使在准备开局期间切换了开关，也不会导致地图被重复重置。
     */
    private boolean skipQueuedMapResetOnce = false;

    /**
     * 仅用于运行时检测“旁观 -> 存活模式”的切换。
     * 不写入 NBT，专门服务于调试时自动回满心情的需求。
     */
    private final HashMap<UUID, Boolean> lastAliveAndSurvivalStates = new HashMap<>();

    private int psychosActive = 0;

    private UUID looseEndWinner;

    private float backfireChance = 0f;

    private int killerDividend = 5;
    private int vigilanteDividend = 5;

    public GameWorldComponent(World world) {
        this.world = world;
    }

    public void sync() {
        GameWorldComponent.KEY.sync(this.world);
    }

    public boolean isBound() {
        return bound;
    }

    public void setBound(boolean bound) {
        this.bound = bound;
        this.sync();
    }

    public int getFade() {
        return fade;
    }

    public void setFade(int fade) {
        this.fade = MathHelper.clamp(fade, 0, GameConstants.FADE_TIME + GameConstants.FADE_PAUSE);
    }

    public void setGameStatus(GameStatus gameStatus) {
        this.gameStatus = gameStatus;
        this.sync();
    }

    public GameStatus getGameStatus() {
        return gameStatus;
    }

    public boolean isRunning() {
        return this.gameStatus == GameStatus.ACTIVE || this.gameStatus == GameStatus.STOPPING;
    }

    public void addRole(PlayerEntity player, Role role) {
        this.addRole(player.getUuid(), role);
    }

    public void addRole(UUID player, Role role) {
        Role oldRole = this.roles.put(player, role);

        /*
         * 对局已经开始时，角色变化要实时记录下来。
         * 这样后续像 noellesroles 的换职、叛变、中立转杀手等流程，
         * 只要最终仍然走本体的角色映射，就会自动留下新旧职业记录。
         */
        if (this.world instanceof ServerWorld serverWorld && this.isRunning() && oldRole != role) {
            GameRecordManager.recordRoleChange(serverWorld, player, oldRole, role);
        }
    }

    public void resetRole(Role role) {
        roles.entrySet().removeIf(entry -> entry.getValue() == role);
    }

    public void setRoles(List<UUID> players, Role role) {
        resetRole(role);

        for (UUID player : players) {
            addRole(player, role);
        }
    }

    public HashMap<UUID, Role> getRoles() {
        return roles;
    }

    public Role getRole(PlayerEntity player) {
        return getRole(player.getUuid());
    }

    public @Nullable Role getRole(UUID uuid) {
        return roles.get(uuid);
    }

    public List<UUID> getAllKillerTeamPlayers() {
        List<UUID> ret = new ArrayList<>();
        roles.forEach((uuid, playerRole) -> {
            if (playerRole.canUseKiller()) {
                ret.add(uuid);
            }
        });

        return ret;
    }
    public List<UUID> getAllWithRole(Role role) {
        List<UUID> ret = new ArrayList<>();
        roles.forEach((uuid, playerRole) -> {
            if (playerRole == role) {
                ret.add(uuid);
            }
        });

        return ret;
    }

    public boolean isRole(@NotNull PlayerEntity player, Role role) {
        return isRole(player.getUuid(), role);
    }

    public boolean isRole(@NotNull UUID uuid, Role role) {
        return this.roles.get(uuid) == role;
    }

    public boolean canUseKillerFeatures(@NotNull PlayerEntity player) {
        return getRole(player) != null && getRole(player).canUseKiller();
    }
    public boolean isInnocent(@NotNull PlayerEntity player) {
        return getRole(player) != null && getRole(player).isInnocent();
    }

    /**
     * 判断玩家当前是否属于指定阵营。
     *
     * <p>后续像结算页、欢迎文本、扩展职业联动左轮冷却这类逻辑，
     * 都应该优先走阵营判断，而不是继续混用 {@code isInnocent()} /
     * {@code canUseKiller()} / “是否等于原版职业对象” 这些旧语义。</p>
     */
    public boolean isFaction(@NotNull PlayerEntity player, @NotNull Faction faction) {
        Role role = getRole(player);
        return role != null && role.getFaction() == faction;
    }

    /**
     * 统计当前对局内某个阵营的玩家数量。
     *
     * <p>这里统计的是“当前被映射到该阵营的角色数”，
     * 适合扩展模组在开局分配阶段按阵营位数做额外限制。</p>
     */
    public int getFactionPlayerCount(@NotNull Faction faction) {
        int count = 0;
        for (Role role : this.roles.values()) {
            if (role != null && role.getFaction() == faction) {
                count++;
            }
        }
        return count;
    }

    public void clearRoleMap() {
        this.roles.clear();
        setPsychosActive(0);
    }

    public void queueMapReset() {
        ticksUntilNextResetAttempt = 10;
    }

    /**
     * 返回新开局是否使用渐进式地图重置流程。
     */
    public boolean isGradualResetEnabled() {
        return gradualResetEnabled;
    }

    /**
     * 更新渐进式重置开关。
     * 新值只影响后续开局，不会取消已经在运行中的渐进式重置任务。
     */
    public void setGradualResetEnabled(boolean gradualResetEnabled) {
        this.gradualResetEnabled = gradualResetEnabled;
        this.sync();
    }

    /**
     * 返回当前是否有渐进式重置任务正在执行。
     */
    public boolean isGradualResetInProgress() {
        return activeResetTask != null && !activeResetTask.isFinished();
    }

    /**
     * 启动一个新的渐进式重置任务。
     * 调用方负责提前构造好带完成回调的任务对象。
     */
    public void startGradualReset(MapResetTask task) {
        this.activeResetTask = task;
    }

    /**
     * 取消当前正在执行的渐进式重置任务。
     * 用于开局流程在重置完成前被中断的情况。
     */
    public void cancelGradualReset() {
        this.activeResetTask = null;
        this.skipQueuedMapResetOnce = false;
    }

    /**
     * 标记下一次初始化时跳过一次原版排队重置。
     * 该标记会在渐进式重置完成、即将进入 STARTING 前被设置。
     */
    public void setSkipQueuedMapResetOnce(boolean skipQueuedMapResetOnce) {
        this.skipQueuedMapResetOnce = skipQueuedMapResetOnce;
    }

    /**
     * 读取并消费“跳过下一次排队重置”的标记。
     *
     * @return 如果接下来的初始化应该跳过一次 {@link #queueMapReset()} 则返回 {@code true}
     */
    public boolean consumeSkipQueuedMapResetOnce() {
        boolean shouldSkip = this.skipQueuedMapResetOnce;
        this.skipQueuedMapResetOnce = false;
        return shouldSkip;
    }

    public int getPsychosActive() {
        return psychosActive;
    }

    public boolean isPsychoActive() {
        return psychosActive > 0;
    }

    public void setPsychosActive(int psychosActive) {
        this.psychosActive = Math.max(0, psychosActive);
        this.sync();
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public void setGameMode(GameMode gameMode) {
        this.gameMode = gameMode;
        this.sync();
    }

    public MapEffect getMapEffect() {
        return mapEffect;
    }

    public void setMapEffect(MapEffect mapEffect) {
        this.mapEffect = mapEffect;
        this.sync();
    }

    public UUID getLooseEndWinner() {
        return this.looseEndWinner;
    }

    public void setLooseEndWinner(UUID looseEndWinner) {
        this.looseEndWinner = looseEndWinner;
        this.sync();
    }

    public boolean isLockedToSupporters() {
        //return lockedToSupporters;
        return false;
    }

    public void setLockedToSupporters(boolean lockedToSupporters) {
        //this.lockedToSupporters = lockedToSupporters;
        this.lockedToSupporters = false;
    }

    public float getBackfireChance() {
        return backfireChance;
    }

    public void setBackfireChance(float backfireChance) {
        this.backfireChance = backfireChance;
        this.sync();
    }

    public int getKillerDividend() {
        return killerDividend;
    }

    public void setKillerDividend(int killerDividend) {
        this.killerDividend = killerDividend;
        this.sync();
    }

    public int getVigilanteDividend() {
        return vigilanteDividend;
    }

    public void setVigilanteDividend(int vigilanteDividend) {
        this.vigilanteDividend = vigilanteDividend;
        this.sync();
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound nbtCompound, RegistryWrapper.WrapperLookup wrapperLookup) {


// 3. NBT 读取 (readFromNbt)
this.fixedKillerCount = nbtCompound.contains("FixedKillerCount") ? nbtCompound.getInt("FixedKillerCount") : -1;


        this.lockedToSupporters = false;
        this.enableWeights = nbtCompound.getBoolean("EnableWeights");
        this.moodEffectDeathEnabled = !nbtCompound.contains("MoodEffectDeathEnabled") || nbtCompound.getBoolean("MoodEffectDeathEnabled");
        this.allowAlivePlayersJump = !nbtCompound.contains("AllowAlivePlayersJump") || nbtCompound.getBoolean("AllowAlivePlayersJump");
        this.alivePlayersCollisionEnabled = !nbtCompound.contains("AlivePlayersCollisionEnabled") || nbtCompound.getBoolean("AlivePlayersCollisionEnabled");

        this.gameMode = WatheGameModes.GAME_MODES.get(Identifier.of(nbtCompound.getString("GameMode")));
        this.mapEffect = WatheMapEffects.MAP_EFFECTS.get(Identifier.of(nbtCompound.getString("MapEffect")));
        this.gameStatus = GameStatus.valueOf(nbtCompound.getString("GameStatus"));

        this.fade = nbtCompound.getInt("Fade");
        this.psychosActive = nbtCompound.getInt("PsychosActive");

        this.backfireChance = nbtCompound.getFloat("BackfireChance");

        this.killerDividend = nbtCompound.getInt("KillerDividend");
        this.vigilanteDividend = nbtCompound.getInt("VigilanteDividend");

        if (nbtCompound.contains("GradualResetEnabled")) {
            this.gradualResetEnabled = nbtCompound.getBoolean("GradualResetEnabled");
        } else {
            this.gradualResetEnabled = true;
        }

        for (Role role : WatheRoles.ROLES) {
            this.setRoles(uuidListFromNbt(nbtCompound, role.identifier().toString()), role);
        }

        if (nbtCompound.contains("LooseEndWinner")) {
            this.looseEndWinner = nbtCompound.getUuid("LooseEndWinner");
        } else {
            this.looseEndWinner = null;
        }
    }

    private ArrayList<UUID> uuidListFromNbt(NbtCompound nbtCompound, String listName) {
        ArrayList<UUID> ret = new ArrayList<>();
        for (NbtElement e : nbtCompound.getList(listName, NbtElement.INT_ARRAY_TYPE)) {
            ret.add(NbtHelper.toUuid(e));
        }
        return ret;
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound nbtCompound, RegistryWrapper.WrapperLookup wrapperLookup) {
    // 4. NBT 写入 (writeToNbt)
        nbtCompound.putInt("FixedKillerCount", fixedKillerCount);
        nbtCompound.putBoolean("LockedToSupporters", lockedToSupporters);
        nbtCompound.putBoolean("EnableWeights", enableWeights);
        nbtCompound.putBoolean("MoodEffectDeathEnabled", moodEffectDeathEnabled);
        nbtCompound.putBoolean("AllowAlivePlayersJump", allowAlivePlayersJump);
        nbtCompound.putBoolean("AlivePlayersCollisionEnabled", alivePlayersCollisionEnabled);

        nbtCompound.putString("GameMode", this.gameMode != null ? this.gameMode.identifier.toString() : "");
        nbtCompound.putString("MapEffect", this.mapEffect != null ? this.mapEffect.identifier.toString() : "");
        nbtCompound.putString("GameStatus", this.gameStatus.toString());

        nbtCompound.putInt("Fade", fade);
        nbtCompound.putInt("PsychosActive", psychosActive);

        nbtCompound.putFloat("BackfireChance", backfireChance);

        nbtCompound.putInt("KillerDividend", killerDividend);
        nbtCompound.putInt("VigilanteDividend", vigilanteDividend);
        nbtCompound.putBoolean("GradualResetEnabled", gradualResetEnabled);

        for (Role role : WatheRoles.ROLES) {
            nbtCompound.put(role.identifier().toString(), nbtFromUuidList(getAllWithRole(role)));
        }

        if (this.looseEndWinner != null) nbtCompound.putUuid("LooseEndWinner", this.looseEndWinner);
    }

    private NbtList nbtFromUuidList(List<UUID> list) {
        NbtList ret = new NbtList();
        for (UUID player : list) {
            ret.add(NbtHelper.fromUuid(player));
        }
        return ret;
    }

    /**
     * 检测玩家是否刚刚从旁观/非存活状态切回到游戏内存活状态。
     * 一旦发生这种切换，就把心情直接回满，方便联机或本地调试。
     */
    private void refreshMoodForPlayersReturningToLife(@NotNull ServerWorld serverWorld) {
        if (!this.isRunning()) {
            this.lastAliveAndSurvivalStates.clear();
            return;
        }

        ArrayList<UUID> currentPlayers = new ArrayList<>();
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            UUID uuid = player.getUuid();
            currentPlayers.add(uuid);

            boolean aliveAndSurvival = GameFunctions.isPlayerAliveAndSurvival(player);
            Boolean lastState = this.lastAliveAndSurvivalStates.put(uuid, aliveAndSurvival);
            if (lastState != null && !lastState && aliveAndSurvival) {
                PlayerMoodComponent.KEY.get(player).setMood(1f);
            }
        }

        this.lastAliveAndSurvivalStates.keySet().removeIf(uuid -> !currentPlayers.contains(uuid));
    }

    @Override
    public void clientTick() {
        tickCommon();

        if (this.isRunning()) {
            gameMode.tickClientGameLoop();
        }
    }


    @Override
    public void serverTick() {
        tickCommon();

        if (!(this.world instanceof ServerWorld serverWorld)) {
            return;
        }

        refreshMoodForPlayersReturningToLife(serverWorld);

        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);

        // 先推进渐进式重置任务，再处理原版的一次性重置队列。
        // 任务完成后会在这里自动清掉运行期引用。
        if (activeResetTask != null) {
            if (activeResetTask.tick()) {
                activeResetTask = null;
            }
        }

        // attempt to reset the play area
        if (--ticksUntilNextResetAttempt == 0) {
            if (GameFunctions.tryResetTrain(serverWorld)) {
                queueMapReset();
            } else {
                ticksUntilNextResetAttempt = -1;
            }
        }

        // if not running and spectators or not in lobby reset them
        if (serverWorld.getTime() % 20 == 0) {
            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                if (!isRunning() && (player.isSpectator() && serverWorld.getServer().getPermissionLevel(player.getGameProfile()) < 2 || (GameFunctions.isPlayerAliveAndSurvival(player) && areas.playArea.contains(player.getPos())))) {
                    GameFunctions.resetPlayer(player);
                }
            }
        }

        if (serverWorld.getServer().getOverworld().equals(serverWorld)) {
            TrainWorldComponent trainComponent = TrainWorldComponent.KEY.get(serverWorld);

            // spectator limits
            if (trainComponent.getSpeed() > 0) {
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    if (!GameFunctions.isPlayerAliveAndSurvival(player) && isBound()) {
                        GameFunctions.limitPlayerToBox(player, areas.playArea);
                    }
                }
            }

            if (this.isRunning()) {
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    if (GameFunctions.isPlayerAliveAndSurvival(player)) {
                        // kill players who fell off the train
                        if (player.getY() < areas.playArea.minY) {
                            GameFunctions.killPlayer(player, false, player.getLastAttacker() instanceof PlayerEntity killerPlayer ? killerPlayer : null, GameConstants.DeathReasons.FELL_OUT_OF_TRAIN);
                        }

                        // put players with no role in spectator mode
                        if (GameWorldComponent.KEY.get(world).getRole(player) == null) {
                            player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                        }
                    }

                }


                // run game loop logic
                gameMode.tickServerGameLoop(serverWorld, this);
            }
        }

        if (serverWorld.getTime() % 20 == 0) {
            this.sync();
        }
    }

    private void tickCommon() {
        if (gameMode == null) {
            gameMode = WatheGameModes.MURDER;
        }
        if (mapEffect == null) {
            mapEffect = WatheMapEffects.HARPY_EXPRESS_NIGHT;
        }

        // fade and start / stop game
        if (this.getGameStatus() == GameStatus.STARTING || this.getGameStatus() == GameStatus.STOPPING) {
            this.setFade(fade + 1);

            if (this.getFade() >= GameConstants.FADE_TIME + GameConstants.FADE_PAUSE) {
                if (world instanceof ServerWorld serverWorld) {
                    if (this.getGameStatus() == GameStatus.STARTING)
                        GameFunctions.initializeGame(serverWorld);
                    if (this.getGameStatus() == GameStatus.STOPPING)
                        GameFunctions.finalizeGame(serverWorld);
                }
            }
        } else if (this.getGameStatus() == GameStatus.ACTIVE || this.getGameStatus() == GameStatus.INACTIVE) {
            this.setFade(fade - 1);
        }

        if (this.isRunning()) {
            gameMode.tickCommonGameLoop();
        }
    }

}
