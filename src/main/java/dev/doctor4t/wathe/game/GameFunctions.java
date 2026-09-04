package dev.doctor4t.wathe.game;

import com.google.common.collect.Lists;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.MapEffect;
import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.api.appearance.BodyAppearanceApi;
import dev.doctor4t.wathe.api.death.BodySpawnContext;
import dev.doctor4t.wathe.api.death.DeathApi;
import dev.doctor4t.wathe.api.death.DeathContext;
import dev.doctor4t.wathe.api.death.DeathDecision;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.api.event.AllowPlayerDeath;
import dev.doctor4t.wathe.api.event.GameEvents;
import dev.doctor4t.wathe.api.event.ShouldDropOnDeath;
import dev.doctor4t.wathe.api.psycho.PsychoModeApi;
import dev.doctor4t.wathe.api.psycho.PsychoModeProfile;
import dev.doctor4t.wathe.api.psycho.PsychoShieldContext;
import dev.doctor4t.wathe.api.psycho.PsychoShieldResult;
import dev.doctor4t.wathe.cca.*;
import dev.doctor4t.wathe.compat.TrainVoicePlugin;
import dev.doctor4t.wathe.config.datapack.MapRegistry;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.VisualConfig;
import dev.doctor4t.wathe.config.datapack.RoomConfig;
import dev.doctor4t.wathe.entity.FirecrackerEntity;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheEntities;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.AnnounceEndingPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Clearable;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;

public class GameFunctions {
    private static final List<BiFunction<World, GameMode, GameMode>> START_GAME_MODE_RESOLVERS = new ArrayList<>();
    private static final List<Runnable> AFTER_START_GAME_ATTEMPT = new ArrayList<>();

    /**
     * 额外死亡回放数据的线程本地暂存区。
     *
     * <p>这里专门为了兼容旧扩展模组对 4 参 {@code killPlayer} 方法体内部的 mixin 注入。
     * 如果把 4 参方法改成单纯转调 5 参重载，那些精确钉在旧方法字节码位置上的注入
     * （例如 kinswathe 的 AddDeathReasonMixin）就会直接失效并导致客户端/服务端崩溃。</p>
     *
     * <p>因此当前实现保持：
     * 1. 旧 4 参方法仍然保留完整流程，维持原有注入点；
     * 2. 新 5 参方法只负责把额外回放字段暂存起来，再调用旧 4 参方法；
     * 3. 真正记录 death 事件时，再从这里把额外字段并入。</p>
     */
    private static final ThreadLocal<NbtCompound> PENDING_EXTRA_DEATH_DATA = new ThreadLocal<>();

    /**
     * 把一个物品堆栈的“回放可见信息”写进 NBT。
     *
     * <p>这里统一写入：
     * 1. item: 物品注册 ID，供回放层兜底翻译；
     * 2. item_name: 当下显示名，供需要精确还原显示文本的场景使用。</p>
     *
     * <p>该方法对扩展职业模组开放，后续像自定义枪械、爆炸物、特殊刀具
     * 需要把真实物品名带入 death / shield / hit 回放时，都可以直接复用。</p>
     */
    public static void putReplayItemData(NbtCompound data, ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        data.putString("item", Registries.ITEM.getId(stack.getItem()).toString());
        data.putString("item_name", Text.Serialization.toJsonString(stack.getName(), world.getRegistryManager()));
    }

    /**
     * 为单次伤害创建一份可直接并入回放事件的物品数据。
     */
    public static @Nullable NbtCompound createReplayItemData(ServerWorld world, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        NbtCompound data = new NbtCompound();
        putReplayItemData(data, world, stack);
        return data;
    }

    /**
     * 读取当前这次 killPlayer 调用临时挂载的额外死亡回放数据。
     *
     * <p>这个访问口主要给扩展模组的“免伤 / 改写死亡 / 条件拦截”逻辑使用：
     * 当它们需要在 {@link AllowPlayerDeath} 里判断本次死亡背后额外附带了哪些回放字段时，
     * 可以安全读取这里，而不用自己重复维护一份线程本地状态。</p>
     *
     * <p>返回值始终是拷贝，调用方可放心读取和改写，不会污染原始待提交数据。</p>
     */
    public static @Nullable NbtCompound getPendingExtraDeathData() {
        NbtCompound pending = PENDING_EXTRA_DEATH_DATA.get();
        return pending == null ? null : pending.copy();
    }

    public static void limitPlayerToBox(ServerPlayerEntity player, Box box) {
        Vec3d playerPos = player.getPos();

        if (!box.contains(playerPos)) {
            double x = playerPos.getX();
            double y = playerPos.getY();
            double z = playerPos.getZ();

            if (z < box.minZ) {
                z = box.minZ;
            }
            if (z > box.maxZ) {
                z = box.maxZ;
            }

            if (y < box.minY) {
                y = box.minY;
            }
            if (y > box.maxY) {
                y = box.maxY;
            }

            if (x < box.minX) {
                x = box.minX;
            }
            if (x > box.maxX) {
                x = box.maxX;
            }

            player.requestTeleport(x, y, z);
        }
    }

    /**
     * 开始一局游戏的准备流程。
     *
     * <p>当启用渐进式重置时，会先在大厅阶段完成地图恢复，
     * 完成后才进入原版的 STARTING 淡入淡出流程。
     * 当关闭渐进式重置时，则保持原版行为，直接进入 STARTING。</p>
     */
    public static void startGame(ServerWorld world, GameMode gameMode, MapEffect mapEffect, int time) {
        try {
            MapVotingComponent votingComponent = MapVotingComponent.KEY.get(world.getServer().getScoreboard());
            if (votingComponent.isVotingActive()) {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    player.sendMessage(Text.translatable("game.start_error.voting_active"), true);
                }
                return;
            }

            GameMode resolvedGameMode = resolveStartGameMode(world, gameMode);
            GameWorldComponent game = GameWorldComponent.KEY.get(world);
            MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(world);
            int playerCount = Math.toIntExact(world.getPlayers().stream().filter(serverPlayerEntity -> isPlayerInReadyArea(serverPlayerEntity, areas)).count());
            /*
             * 保存“本次真正准备启动的模式”，而不是命令或大厅最初传入的模式。
             * HarpyModLoader 默认会把 Murder 开局替换成 harpymodloader:modded；
             * 如果不在人数判断前解析，Harpy 独立的开局人数配置就不会生效。
             */
            game.setGameMode(resolvedGameMode);
            game.setMapEffect(mapEffect);
            GameTimeComponent.KEY.get(world).setResetTime(time);
            int requiredPlayerCount = getRequiredStartPlayerCount(world, resolvedGameMode);

            if (playerCount >= requiredPlayerCount) {
                if (game.isGradualResetEnabled()) {
                    // 如果渐进式重置已经在运行，则忽略重复开局请求。
                    if (game.isGradualResetInProgress()) {
                        return;
                    }

                    MapResetTask task = new MapResetTask(world, () -> {
                        // 地图已经在 initializeGame 之前恢复完成，
                        // 因此下一次初始化时要跳过原版的一次性排队重置。
                        game.setSkipQueuedMapResetOnce(true);
                        game.setGameStatus(GameWorldComponent.GameStatus.STARTING);
                    });
                    game.startGradualReset(task);
                } else {
                    game.setGameStatus(GameWorldComponent.GameStatus.STARTING);
                }
            } else {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    player.sendMessage(Text.translatable("game.start_error.not_enough_players", requiredPlayerCount), true);
                }
            }
        } finally {
            /*
             * 扩展可能在 startGame 前临时放置“一次性开局意图”。
             * 不论本次因为投票、人数不足、地图渐进式重置已在运行而成功或失败，
             * 都要给扩展一次收尾机会，避免下一次自动开局读到陈旧状态。
             */
            for (Runnable listener : AFTER_START_GAME_ATTEMPT) {
                listener.run();
            }
        }
    }

    /**
     * 注册“开局前游戏模式解析器”。
     *
     * <p>这个入口留给 HarpyModLoader 这类扩展：它们可能在用户选择 Murder 时，
     * 实际想启动自己的 modded murder 模式。解析发生在人数判断、自动开局倒计时和
     * GameWorldComponent 保存模式之前，因此按模式配置的开局人数、HUD 文本和最终开局模式
     * 会保持一致。</p>
     */
    public static void registerStartGameModeResolver(BiFunction<World, GameMode, GameMode> resolver) {
        START_GAME_MODE_RESOLVERS.add(resolver);
    }

    /**
     * 注册开局尝试结束后的收尾监听。
     *
     * <p>主要用于清理扩展在 startGame 之前设置的临时状态。
     * 监听会在人数不足、投票阻止、重复开局请求等提前返回路径之后照常执行。</p>
     */
    public static void registerAfterStartGameAttempt(Runnable listener) {
        AFTER_START_GAME_ATTEMPT.add(listener);
    }

    /**
     * 解析本次开局真正使用的游戏模式。
     *
     * <p>解析器返回 {@code null} 表示不处理，继续交给后面的解析器；
     * 返回非空模式则作为新的候选模式继续向后传递，方便多个扩展按顺序叠加。</p>
     */
    public static GameMode resolveStartGameMode(@NotNull World world, @Nullable GameMode requestedGameMode) {
        GameMode resolvedGameMode = requestedGameMode == null ? WatheGameModes.MURDER : requestedGameMode;
        for (BiFunction<World, GameMode, GameMode> resolver : START_GAME_MODE_RESOLVERS) {
            GameMode replacement = resolver.apply(world, resolvedGameMode);
            if (replacement != null) {
                resolvedGameMode = replacement;
            }
        }
        return resolvedGameMode;
    }

    /**
     * 解析当前世界真正用于“是否允许开局”的准备区玩家人数。
     *
     * <p>这里是手动开局、喇叭开局、自动开局和大厅 HUD 共用的唯一入口：
     * 1. GameMode.minPlayerCount 仍保留为模式自身的硬性最低人数，兼容 Loose Ends 等特殊模式；
     * 2. GameWorldComponent 里的按模式开局人数配置，是服务器可通过指令调整的玩法门槛；
     * 3. 两者取较大值，避免某些模式被指令调到低于自身玩法能运行的最低人数。</p>
     *
     * <p>这里不会再按 killerDividend 自动抬高人数。若管理员把人数设置得低于杀手比例分母，
     * 视为调试测试局，由管理员自行通过强制职业、固定杀手数等指令安排本局身份。</p>
     */
    public static int getRequiredStartPlayerCount(@NotNull World world, @Nullable GameMode gameMode) {
        GameWorldComponent game = GameWorldComponent.KEY.get(world);
        int configuredMinimum = game.getRequiredStartPlayerCountSetting(gameMode == null ? null : gameMode.identifier);
        int modeMinimum = gameMode == null ? 1 : gameMode.minPlayerCount;
        return Math.max(1, Math.max(configuredMinimum, modeMinimum));
    }

    public static void stopGame(ServerWorld world) {
        GameWorldComponent component = GameWorldComponent.KEY.get(world);
        component.cancelGradualReset();
        component.setGameStatus(GameWorldComponent.GameStatus.STOPPING);
    }

    /**
     * 在游戏进入 STARTING 后执行原版初始化流程。
     * 渐进式重置只改变 STARTING 之前的地图恢复阶段；
     * 一旦进入这里，后续角色分配和模式初始化仍然沿用原版 Wathe 流程。
     */
    public static void initializeGame(ServerWorld serverWorld) {
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(serverWorld);
        List<ServerPlayerEntity> readyPlayerList = getReadyPlayerList(serverWorld);

        GameEvents.ON_GAME_START.invoker().onGameStart(gameComponent.getGameMode());
        /*
         * 回放记录在这里提前创建，但会一直等到全部初始化监听器跑完后，
         * 才正式固化“初始职业快照”并开放后续转职记录。
         * 这样可兼容 Harpy 以及各类扩展职业模组在开局阶段的二次赋职流程。
         */
        GameRecordManager.startMatch(serverWorld, gameComponent);
        baseInitialize(serverWorld, gameComponent, readyPlayerList);
        /*
         * 权重历史是全服 scoreboard 级数据，但只有 Murder/Harpy modded 真正使用阵营抽取。
         * Loose Ends 和 Discovery 不能推进全服轮次，否则一次非 Murder 娱乐局也会让所有玩家
         * 的有效历史衰减，造成下一局权重无故变化。
         */
        if (ScoreboardRoleSelectorComponent.tracksGameMode(gameComponent.getGameMode())) {
            ScoreboardRoleSelectorComponent.KEY.get(serverWorld.getScoreboard()).beginAssignmentRound();
        }
        gameComponent.getGameMode().initializeGame(serverWorld, gameComponent, readyPlayerList);

        gameComponent.sync();

        GameEvents.ON_FINISH_INITIALIZE.invoker().onFinishInitialize(serverWorld, gameComponent);
        /*
         * 职业权重必须在所有开局初始化监听之后记录。
         * HarpyModLoader 会先让玩家拿到原版阵营位，再把它替换为扩展职业；
         * 其他扩展也可能在 ON_FINISH_INITIALIZE 里做最后一次职业调整。
         * 放到这里记录，才能确保“开局强制职业”和“最终扩展职业”都计入下局权重，
         * 而局内管理员用 /setRole 调试转职不会被误写进开局分配历史。
         */
        ScoreboardRoleSelectorComponent.KEY.get(serverWorld.getScoreboard()).recordRoundAssignments(serverWorld, readyPlayerList, gameComponent);
        GameRecordManager.completeInitialization(serverWorld, gameComponent);
    }

    private static void baseInitialize(ServerWorld serverWorld, GameWorldComponent gameComponent, List<ServerPlayerEntity> players) {
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);
        MapEnhancementsWorldComponent enhancements = MapEnhancementsWorldComponent.KEY.get(serverWorld);

        WorldBlackoutComponent.KEY.get(serverWorld).reset();

        serverWorld.getGameRules().get(GameRules.KEEP_INVENTORY).set(true, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.DO_TRADER_SPAWNING).set(false, serverWorld.getServer());
        serverWorld.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE).set(9999, serverWorld.getServer());
        serverWorld.getServer().setDifficulty(Difficulty.PEACEFUL, true);

        // dismount all players as it can cause issues
        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            player.dismountVehicle();
            /*
             * 新局初始化时先清掉上一局或调试留下的特殊存活授权。
             * 后续真正参与本局的玩家会被切回冒险模式；没有参与本局的玩家会被切到普通旁观，
             * 两边都不应该继承旧的“旁观/创造仍存活”标记。
             */
            PlayerLifeStateApi.clearAliveOverride(player);
        }

        for (ServerPlayerEntity player : players) {
            player.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
        }

        // teleport non playing players
        for (ServerPlayerEntity player : serverWorld.getPlayers(serverPlayerEntity -> !players.contains(serverPlayerEntity))) {
            player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);

            MapVariablesWorldComponent.PosWithOrientation spectatorSpawnPos = areas.getSpectatorSpawnPos();
            player.teleport(serverWorld, spectatorSpawnPos.pos.getX(), spectatorSpawnPos.pos.getY(), spectatorSpawnPos.pos.getZ(), spectatorSpawnPos.yaw, spectatorSpawnPos.pitch);
        }

        // clear items, clear previous game data
        for (ServerPlayerEntity serverPlayerEntity : players) {
            serverPlayerEntity.getInventory().clear();
            PlayerMoodComponent.KEY.get(serverPlayerEntity).reset();
            PlayerStaminaComponent.KEY.get(serverPlayerEntity).reset();
            PlayerShopComponent.KEY.get(serverPlayerEntity).reset();
            PlayerPoisonComponent.KEY.get(serverPlayerEntity).reset();
            PlayerPsychoComponent.KEY.get(serverPlayerEntity).reset();
            PlayerNoteComponent.KEY.get(serverPlayerEntity).reset();
            PlayerBlackoutEffectComponent.KEY.get(serverPlayerEntity).clearOwnedEffect();
            PlayerShopComponent.KEY.get(serverPlayerEntity).reset();
            TrainVoicePlugin.resetPlayer(serverPlayerEntity.getUuid());

            // remove item cooldowns
            HashSet<Item> copy = new HashSet<>(serverPlayerEntity.getItemCooldownManager().entries.keySet());
            for (Item item : copy) serverPlayerEntity.getItemCooldownManager().remove(item);
        }
        gameComponent.clearRoleMap();
        GameTimeComponent.KEY.get(serverWorld).reset();

        // 只有在地图没有被渐进式任务提前恢复时，
        // 才继续排队执行原版的一次性地图重置。
        if (!gameComponent.consumeSkipQueuedMapResetOnce()) {
            gameComponent.queueMapReset();
        }

        // map effect initialize
        gameComponent.getMapEffect().initializeMapEffects(serverWorld, players);

        applyMapEnhancementsAtRoundStart(serverWorld, gameComponent, enhancements);

        if (enhancements.getRoomCount() > 0) {
            assignConfiguredRooms(serverWorld, gameComponent, enhancements, players);
        } else {
            teleportPlayersToDefaultPlayArea(areas, players);
        }

        /*
         * 这里记录本局真正开始进入 ACTIVE 的世界 tick。
         * 后续“开局多少秒内不启用玩家碰撞体积”的逻辑，就以这个时刻为起点计算。
         */
        gameComponent.markAlivePlayersCollisionRoundStart(serverWorld.getTime());
        gameComponent.setGameStatus(GameWorldComponent.GameStatus.ACTIVE);
        gameComponent.sync();
    }

    private static void applyMapEnhancementsAtRoundStart(ServerWorld serverWorld, GameWorldComponent gameComponent, MapEnhancementsWorldComponent enhancements) {
        TrainWorldComponent train = TrainWorldComponent.KEY.get(serverWorld);

        if (enhancements.hasVisualConfig()) {
            VisualConfig visual = enhancements.getVisualConfig();
            train.setHud(visual.hud());
            train.setSpeed(visual.staticMap() ? 0 : visual.trainSpeed());
            /*
             * 和 /wathe:setVisual time 一样，地图 JSON 的开局视觉时间也应用到所有维度。
             * 这样多维度客户端不会出现短暂变夜后又被其他世界时间同步抢回白天。
             */
            TrainWorldComponent.setServerTimeOfDay(serverWorld.getServer(), visual.timeOfDay());
        }

        if (enhancements.hasFogConfig()) {
            // fog.enabled 用来覆盖地图效果里的默认雾开关，方便静态/特殊地图单独关闭。
            train.setFog(enhancements.getFogConfig().enabled());
        }

        if (enhancements.hasSnowParticlesConfig()) {
            // snow_particles.enabled 同样只作为地图开局默认值，客户端粒子数量仍读取完整 JSON。
            train.setSnow(enhancements.getSnowParticlesConfig().enabled());
        }

        if (enhancements.hasJumpConfig()) {
            /*
             * JSON 只在开局时执行一次默认跳跃规则。
             * 对局开始后 /wathe:allowjump 仍然直接改 GameWorldComponent，因此指令优先级最高。
             */
            gameComponent.setAlivePlayerJumpAllowed(enhancements.getJumpConfig().allowed());
        }
    }

    private static void teleportPlayersToDefaultPlayArea(MapVariablesWorldComponent areas, List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            Vec3d pos = player.getPos().add(Vec3d.of(areas.getPlayAreaOffset()));
            player.requestTeleport(pos.getX(), pos.getY() + 1, pos.getZ());
        }
    }

    private static void assignConfiguredRooms(ServerWorld serverWorld, GameWorldComponent gameComponent, MapEnhancementsWorldComponent enhancements, List<ServerPlayerEntity> players) {
        Random random = new Random();
        Map<Integer, Integer> roomPlayerCounts = new HashMap<>();
        List<ServerPlayerEntity> shuffledPlayers = new ArrayList<>(players);
        Collections.shuffle(shuffledPlayers, random);

        for (ServerPlayerEntity player : shuffledPlayers) {
            removeRoomKeys(player);

            int roomNumber = findRandomAvailableRoom(roomPlayerCounts, enhancements, enhancements.getRoomCount(), random);
            int playerIndexInRoom = roomPlayerCounts.getOrDefault(roomNumber, 0);
            String roomName = enhancements.getRoomConfig(roomNumber)
                    .map(config -> config.getName(roomNumber))
                    .orElse("Room " + roomNumber);

            gameComponent.addPlayerToRoom(roomNumber, roomName, player);
            roomPlayerCounts.put(roomNumber, playerIndexInRoom + 1);
            giveRoomKey(player, roomName);

            Optional<RoomConfig.SpawnPoint> spawnPoint = enhancements.getSpawnPointForPlayer(roomNumber, playerIndexInRoom);
            if (spawnPoint.isPresent()) {
                teleportToConfiguredSpawn(serverWorld, player, spawnPoint.get());
            } else {
                Wathe.LOGGER.warn("Room {} on dimension {} has no spawn point, keeping player {} at current position",
                        roomNumber, serverWorld.getRegistryKey().getValue(), player.getGameProfile().getName());
            }
        }
    }

    private static int findRandomAvailableRoom(Map<Integer, Integer> roomPlayerCounts, MapEnhancementsWorldComponent enhancements, int totalRooms, Random random) {
        List<Integer> availableRooms = new ArrayList<>();
        int minCount = Integer.MAX_VALUE;

        for (int i = 1; i <= totalRooms; i++) {
            int currentCount = roomPlayerCounts.getOrDefault(i, 0);
            int maxPlayers = enhancements.getRoomConfig(i)
                    .map(RoomConfig::getMaxPlayers)
                    .map(count -> Math.max(1, count))
                    .orElse(1);

            if (currentCount < maxPlayers) {
                if (currentCount < minCount) {
                    minCount = currentCount;
                    availableRooms.clear();
                    availableRooms.add(i);
                } else if (currentCount == minCount) {
                    availableRooms.add(i);
                }
            }
        }

        if (!availableRooms.isEmpty()) {
            return availableRooms.get(random.nextInt(availableRooms.size()));
        }

        int totalPlayers = roomPlayerCounts.values().stream().mapToInt(Integer::intValue).sum();
        return totalRooms <= 0 ? 1 : (totalPlayers % totalRooms) + 1;
    }

    private static void giveRoomKey(ServerPlayerEntity player, String roomName) {
        ItemStack itemStack = new ItemStack(WatheItems.KEY);
        itemStack.apply(DataComponentTypes.LORE, LoreComponent.DEFAULT, component -> new LoreComponent(
                Text.literal(roomName).getWithStyle(Style.EMPTY.withItalic(false).withColor(0xFF8C00))
        ));
        player.giveItemStack(itemStack);
    }

    private static void removeRoomKeys(ServerPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(WatheItems.KEY)) {
                player.getInventory().setStack(slot, ItemStack.EMPTY);
            }
        }
    }

    private static void teleportToConfiguredSpawn(ServerWorld serverWorld, ServerPlayerEntity player, RoomConfig.SpawnPoint spawnPoint) {
        BlockPos chunkPos = BlockPos.ofFloored(spawnPoint.x(), spawnPoint.y(), spawnPoint.z());
        serverWorld.getChunk(chunkPos);
        player.teleport(serverWorld, spawnPoint.x(), spawnPoint.y(), spawnPoint.z(), spawnPoint.yaw(), spawnPoint.pitch());
    }

    private static List<ServerPlayerEntity> getReadyPlayerList(ServerWorld serverWorld) {
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);
        List<ServerPlayerEntity> players = serverWorld.getPlayers(serverPlayerEntity -> isPlayerInReadyArea(serverPlayerEntity, areas));
        return players;
    }

    private static boolean isPlayerInReadyArea(PlayerEntity player, MapVariablesWorldComponent areas) {
        /*
         * 准备区只负责判断“谁想参加下一局”。
         * 创造/旁观玩家在开局时会被 baseInitialize 统一切回冒险模式，
         * 所以这里不能复用局内胜负用的 isPlayerAliveAndSurvival 判定。
         */
        return player != null && areas.getReadyArea().contains(player.getPos());
    }

    public static void finalizeGame(ServerWorld world) {
        GameWorldComponent gameComponent = GameWorldComponent.KEY.get(world);
        gameComponent.cancelGradualReset();
        GameEvents.ON_GAME_STOP.invoker().onGameStop(gameComponent.getGameMode());
        gameComponent.getGameMode().finalizeGame(world, gameComponent);

        WorldBlackoutComponent.KEY.get(world).reset();
        TrainWorldComponent trainComponent = TrainWorldComponent.KEY.get(world);
        trainComponent.setSpeed(0);
        TrainWorldComponent.setServerTimeOfDay(world.getServer(), TrainWorldComponent.TimeOfDay.DAY);

        // discard all player bodies
        for (PlayerBodyEntity body : world.getEntitiesByType(WatheEntities.PLAYER_BODY, playerBodyEntity -> true))
            body.discard();
        for (FirecrackerEntity entity : world.getEntitiesByType(WatheEntities.FIRECRACKER, entity -> true))
            entity.discard();
        for (NoteEntity entity : world.getEntitiesByType(WatheEntities.NOTE, entity -> true)) entity.discard();

        // reset all players
        for (ServerPlayerEntity player : world.getPlayers()) {
            /*
             * 对局结束时，GameRecordHooks 要在本方法返回后才封存并发送完整回放。
             * 旧顺序直接调用 resetPlayer -> PlayerPsychoComponent.reset()，而 reset()
             * 为避免大厅/换局污染会使用 stopPsycho(false)，导致仍在疯魔中的玩家
             * 永远没有 psycho_mode_end 事件。这里先在记录仍有效的窗口显式记录结束，
             * 随后 resetPlayer 再执行不记录回放的常规清理，确保不会重复写入结束事件。
             * 该处理同时覆盖默认疯魔和所有通过 PsychoModeProfile 接入的特殊疯魔。
             */
            PlayerPsychoComponent psycho = PlayerPsychoComponent.KEY.get(player);
            if (psycho.isPsychoActive()) {
                psycho.stopPsycho(true);
            }
            PlayerLifeStateApi.clearAliveOverride(player);
            resetPlayer(player);
        }

        // reset game component
        GameTimeComponent.KEY.get(world).reset();
        gameComponent.clearRoleMap();
        gameComponent.setGameStatus(GameWorldComponent.GameStatus.INACTIVE);
        gameComponent.clearAlivePlayersCollisionRoundStart();
        trainComponent.setTime(0);
        gameComponent.sync();

        GameEvents.ON_FINISH_FINALIZE.invoker().onFinishFinalize(world, gameComponent);

        if (MapRegistry.getInstance().getMapCount() > 0) {
            MapVotingComponent.KEY.get(world.getServer().getScoreboard()).startVoting();
        }
    }

    public static void resetPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new AnnounceEndingPayload());
        player.dismountVehicle();
        player.getInventory().clear();
        PlayerMoodComponent.KEY.get(player).reset();
        PlayerStaminaComponent.KEY.get(player).reset();
        PlayerShopComponent.KEY.get(player).reset();
        PlayerPoisonComponent.KEY.get(player).reset();
        PlayerPsychoComponent.KEY.get(player).reset();
        PlayerNoteComponent.KEY.get(player).reset();
        PlayerBlackoutEffectComponent.KEY.get(player).clearOwnedEffect();
        PlayerLifeStateApi.clearAliveOverride(player);
        TrainVoicePlugin.resetPlayer(player.getUuid());

        player.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
        player.wakeUp();
        MapVariablesWorldComponent.PosWithOrientation spawnPos = MapVariablesWorldComponent.KEY.get(player.getWorld()).getSpawnPos();
        TeleportTarget teleportTarget = new TeleportTarget(player.getServerWorld(), spawnPos.pos, Vec3d.ZERO, spawnPos.yaw, spawnPos.pitch, TeleportTarget.NO_OP);
        player.teleportTo(teleportTarget);
    }

    /**
     * 从一份 extraDeathData 里仅提取“回放物品字段”。
     *
     * <p>这样做的目的是避免把别的业务字段原样塞进 shield_blocked 事件，
     * 只保留 item / item_name 这类和显示直接相关的内容。</p>
     */
    private static @Nullable NbtCompound copyReplayItemData(@Nullable NbtCompound source) {
        if (source == null) {
            return null;
        }
        NbtCompound result = new NbtCompound();
        if (source.contains("item")) {
            result.putString("item", source.getString("item"));
        }
        if (source.contains("item_name")) {
            result.putString("item_name", source.getString("item_name"));
        }
        return result.isEmpty() ? null : result;
    }

    private static @Nullable NbtCompound createReplayItemIdOnly(Item item) {
        NbtCompound data = new NbtCompound();
        data.putString("item", Registries.ITEM.getId(item).toString());
        return data;
    }

    public static @Nullable Identifier getReplayItemId(@Nullable NbtCompound data) {
        if (data == null || !data.contains("item")) {
            return null;
        }
        return Identifier.tryParse(data.getString("item"));
    }

    /**
     * 为“护盾挡伤”事件统一组装回放数据。
     *
     * <p>这里会始终写入 {@code death_reason}，这样当本次伤害本来没有直接物品来源时，
     * 回放层就可以退回到死因文本，例如“巫毒魔法”“心灵冲击”等，
     * 避免最终显示成“未知物品”。</p>
     *
     * <p>如果这次伤害本身存在明确的物品来源，也会继续把 item / item_name 一并带上，
     * 从而维持原有“优先显示真实命中物品”的效果。</p>
     */
    public static NbtCompound createBlockedDamageReplayData(@Nullable PlayerEntity killer, Identifier deathReason) {
        NbtCompound data = new NbtCompound();
        data.putString("death_reason", deathReason.toString());
        NbtCompound resolvedItemData = resolveDamageReplayData(killer, deathReason, PENDING_EXTRA_DEATH_DATA.get());
        if (resolvedItemData != null) {
            data.copyFrom(resolvedItemData);
        }
        return data;
    }

    /**
     * 统一解析一次伤害在回放里应该显示成什么物品。
     *
     * <p>解析优先级如下：
     * 1. 扩展模组显式塞进来的 extraDeathData 里的 item / item_name；
     * 2. 立即型近战 / 枪击优先读取攻击者当前主手，保证自定义刀具、枪械命名正确；
     * 3. 如果仍然拿不到，则退回 Wathe 本体默认物品，至少不会丢成未知物品。</p>
     *
     * <p>注意：手雷类伤害不会盲目读取攻击者主手，
     * 因为爆炸发生时玩家往往已经切换物品或物品已被消耗。
     * 这类情况优先依赖 extraDeathData；没有时再兜底成本体手雷。</p>
     */
    private static @Nullable NbtCompound resolveDamageReplayData(@Nullable PlayerEntity killer, Identifier deathReason, @Nullable NbtCompound preferredData) {
        NbtCompound preferredItemData = copyReplayItemData(preferredData);
        if (preferredItemData != null) {
            return preferredItemData;
        }

        if (killer instanceof ServerPlayerEntity killerPlayer) {
            if ((deathReason.equals(GameConstants.DeathReasons.KNIFE)
                    || deathReason.equals(GameConstants.DeathReasons.GUN)
                    || deathReason.equals(GameConstants.DeathReasons.BAT))
                    && !killerPlayer.getMainHandStack().isEmpty()) {
                return createReplayItemData(killerPlayer.getServerWorld(), killerPlayer.getMainHandStack());
            }
        }

        if (deathReason.equals(GameConstants.DeathReasons.KNIFE)) {
            return createReplayItemIdOnly(WatheItems.KNIFE);
        }
        if (deathReason.equals(GameConstants.DeathReasons.GUN)) {
            return createReplayItemIdOnly(WatheItems.REVOLVER);
        }
        if (deathReason.equals(GameConstants.DeathReasons.BAT)) {
            return createReplayItemIdOnly(WatheItems.BAT);
        }
        if (deathReason.equals(GameConstants.DeathReasons.GRENADE)) {
            return createReplayItemIdOnly(WatheItems.GRENADE);
        }
        return null;
    }

    /**
     * 把一次“即将造成死亡”的原因还原成回放里应该显示的伤害物品。
     *
     * <p>这里是给护盾挡伤事件使用的轻量包装，真正的解析逻辑统一在
     * {@link #resolveDamageReplayData(PlayerEntity, Identifier, NbtCompound)} 里。</p>
     */
    public static @Nullable Identifier resolveDamageItemForBlockedDeath(@Nullable PlayerEntity killer, Identifier deathReason) {
        return getReplayItemId(resolveDamageReplayData(killer, deathReason, PENDING_EXTRA_DEATH_DATA.get()));
    }

    public static boolean isPlayerEliminated(PlayerEntity player) {
        return player == null || !player.isAlive() || !isPlayerAliveAndSurvival(player);
    }

    @SuppressWarnings("unused")
    public static void killPlayer(PlayerEntity victim, boolean spawnBody, @Nullable PlayerEntity killer) {
        killPlayer(victim, spawnBody, killer, GameConstants.DeathReasons.GENERIC);
    }

    public static void killPlayer(PlayerEntity victim, boolean spawnBody, @Nullable PlayerEntity killer, Identifier deathReason) {
        DeathContext deathContext = new DeathContext(victim, spawnBody, killer, deathReason, PENDING_EXTRA_DEATH_DATA.get());
        if (DeathApi.resolveEarlyInterceptors(deathContext) == DeathDecision.CANCEL) {
            deathContext.markCancelledBeforeAttempt();
            return;
        }

        try {
            /*
             * 这里是“死亡请求已经进入 Wathe 统一流程，但还没有经过免死/护盾/致死确认”的阶段。
             * 旧扩展里钉在 killPlayer HEAD 的连锁死亡和状态标记，应迁移到这个阶段，
             * 再用 priority 明确谁先执行、谁后执行。
             */
            DeathApi.invokeBeforeAttempt(deathContext);

            PlayerPsychoComponent component = PlayerPsychoComponent.KEY.get(victim);

            if (!AllowPlayerDeath.EVENT.invoker().allowDeath(victim, killer, deathReason)) {
                deathContext.markCancelledByProtection();
                return;
            }
            if (component.isPsychoActive()) {
                PsychoModeProfile psychoProfile = component.getProfile();
                NbtCompound damageReplayData = createBlockedDamageReplayData(killer, deathReason);
                PsychoModeApi.putModeReplayData(damageReplayData, psychoProfile);
                PsychoShieldResult shieldResult = PsychoModeApi.resolveShield(new PsychoShieldContext(
                        victim,
                        killer,
                        deathReason,
                        component,
                        psychoProfile,
                        damageReplayData.copy()
                ));
                if (shieldResult == PsychoShieldResult.BLOCK) {
                    if (victim instanceof ServerPlayerEntity victimPlayer) {
                        /*
                         * 统一走解析方法，避免像“手雷爆炸打到护盾”这种并非主手直接命中的伤害
                         * 被错误显示成未知物品。
                         *
                         * 同时这里也必须把 death_reason 带进 shield_blocked，
                         * 这样像扩展模组的巫毒魔法这类“没有物品来源”的伤害，
                         * 才能在护盾回放里退回显示成死因文本，而不是 [未知物品]。
                         */
                        GameRecordManager.recordShieldBlocked(
                                victimPlayer,
                                killer instanceof ServerPlayerEntity killerPlayer ? killerPlayer : null,
                                psychoProfile.shieldSourceId(),
                                getReplayItemId(damageReplayData),
                                damageReplayData
                        );
                    }
                    if (component.getArmour() > 0) {
                        component.setArmour(component.getArmour() - 1);
                    }
                    SoundEvent shieldSound = psychoProfile.shieldSound();
                    if (shieldSound != null) {
                        victim.playSoundToPlayer(shieldSound, SoundCategory.MASTER, 5F, 1F);
                    }
                    deathContext.markBlockedByShield();
                    return;
                }

                if (shieldResult == PsychoShieldResult.BYPASS || component.getArmour() <= 0) {
                    component.stopPsycho();
                }
            }

            if (victim instanceof ServerPlayerEntity serverPlayerEntity && isPlayerAliveAndSurvival(serverPlayerEntity)) {
                /*
                 * 这一步在清特殊存活授权之前，给扩展最后一次把“致死”改写成其它机制的机会。
                 * 例如双重人格活跃人格被击杀时，可以在这里改为“双活解离”并取消本次死亡。
                 */
                if (DeathApi.resolveFatalInterceptors(deathContext) == DeathDecision.CANCEL) {
                    deathContext.markFatalIntercepted();
                    return;
                }

                /*
                 * 真正死亡时必须先清特殊存活授权，再切旁观。
                 * 否则“机制旁观仍存活”的玩家被击杀后，客户端和胜负判定会继续把他当活人。
                 */
                PlayerLifeStateApi.clearAliveOverride(serverPlayerEntity);
                serverPlayerEntity.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                deathContext.markDead();
                DeathApi.invokeAfterMarkedDead(deathContext);

                NbtCompound pendingExtraDeathData = PENDING_EXTRA_DEATH_DATA.get();
                NbtCompound deathData = pendingExtraDeathData == null ? new NbtCompound() : pendingExtraDeathData.copy();
                if (deathReason.equals(GameConstants.DeathReasons.POISON) || deathReason.equals(GameConstants.DeathReasons.BED_POISON)) {
                    PlayerPoisonComponent poisonComponent = PlayerPoisonComponent.KEY.get(victim);
                    if (poisonComponent.getPoisoner() != null) {
                        deathData.putUuid("poisoner", poisonComponent.getPoisoner());
                    }
                    if (poisonComponent.getPoisonData() != null) {
                        NbtCompound poisonData = poisonComponent.getPoisonData();
                        if (poisonData.contains("item")) {
                            deathData.putString("item", poisonData.getString("item"));
                        }
                        if (poisonData.contains("item_name")) {
                            deathData.putString("item_name", poisonData.getString("item_name"));
                        }
                        deathData.put("poison_data", poisonData);
                    }
                }
                NbtCompound damageReplayData = resolveDamageReplayData(killer, deathReason, deathData);
                if (damageReplayData != null) {
                    if (damageReplayData.contains("item") && !deathData.contains("item")) {
                        deathData.putString("item", damageReplayData.getString("item"));
                    }
                    if (damageReplayData.contains("item_name") && !deathData.contains("item_name")) {
                        deathData.putString("item_name", damageReplayData.getString("item_name"));
                    }
                }
                GameRecordManager.recordDeath(
                        serverPlayerEntity,
                        killer instanceof ServerPlayerEntity killerPlayer ? killerPlayer : null,
                        deathReason,
                        deathData.isEmpty() ? null : deathData
                );
                deathContext.markDeathRecorded();
            } else {
                return;
            }

            if (killer != null) {
                boolean defaultKillerReward = GameWorldComponent.KEY.get(killer.getWorld()).canUseKillerFeatures(killer);
                /*
                 * 默认击杀收益不再写死为“杀手能力角色一定获得”。
                 * 扩展可以在 DeathApi 里允许/拒绝这笔 Wathe 默认收益，例如：
                 * 1. 魔术师播放体击杀时阻止假玩家拿钱，再把收益转给真实魔术师；
                 * 2. 未来特殊职业可以让非杀手阵营也获得默认击杀收益；
                 * 3. 额外奖励仍建议放在 afterAttempt，并检查 confirmedDeath()。
                 */
                if (DeathApi.shouldGrantDefaultKillerReward(deathContext, defaultKillerReward)) {
                    PlayerShopComponent killerShop = PlayerShopComponent.KEY.get(killer);
                    killerShop.addToBalance(GameConstants.MONEY_PER_KILL);
                    // 任务币击杀收益目前暂停；常量大于 0 时才发放，方便后续重新启用。
                    if (GameConstants.TASK_MONEY_PER_KILL > 0) {
                        killerShop.addCurrencyAmount(EconomyApi.TASK_MONEY, GameConstants.TASK_MONEY_PER_KILL);
                    }
                }

                // replenish derringer
                for (List<ItemStack> list : killer.getInventory().combinedInventory) {
                    for (ItemStack stack : list) {
                        Boolean used = stack.get(WatheDataComponentTypes.USED);
                        if (stack.isOf(WatheItems.DERRINGER) && used != null && used) {
                            stack.set(WatheDataComponentTypes.USED, false);
                            killer.playSoundToPlayer(WatheSounds.ITEM_DERRINGER_RELOAD, SoundCategory.PLAYERS, 1.0f, 1.0f);
                        }
                    }
                }
            }

            DeathApi.invokeBeforeMoodReset(deathContext);
            PlayerMoodComponent.KEY.get(victim).reset();

            if (spawnBody) {
                PlayerBodyEntity body = WatheEntities.PLAYER_BODY.create(victim.getWorld());
                if (body != null) {
                    body.setPlayerUuid(victim.getUuid());
                    UUID appearanceUuid = BodyAppearanceApi.resolveAppearanceUuid(victim, killer, deathReason);
                    if (appearanceUuid != null) {
                        /*
                         * 这里只写“尸体看起来像谁”，不改 body.setPlayerUuid(...) 里的真实死者。
                         * 这样双重人格等视觉伪装不会影响尸袋、验尸、回放等继续追踪真正死亡玩家的逻辑。
                         */
                        body.setAppearanceUuid(appearanceUuid);
                    }
                    Vec3d spawnPos = victim.getPos().add(victim.getRotationVector().normalize().multiply(1));
                    body.refreshPositionAndAngles(spawnPos.getX(), victim.getY(), spawnPos.getZ(), victim.getHeadYaw(), 0f);
                    body.setYaw(victim.getHeadYaw());
                    body.setHeadYaw(victim.getHeadYaw());
                    /*
                     * 尸体生成回调放在 spawnEntity 之前：
                     * 旧扩展里拿本地变量 body 写 CCA/状态效果的 mixin，都可以在这里安全迁移。
                     */
                    DeathApi.invokeBodySpawn(new BodySpawnContext(deathContext, body, victim, killer, deathReason));
                    deathContext.markBodySpawned();
                    victim.getWorld().spawnEntity(body);
                }
            }

            for (List<ItemStack> list : victim.getInventory().combinedInventory) {
                for (int i = 0; i < list.size(); i++) {
                    ItemStack stack = list.get(i);
                    if (shouldDropOnDeath(stack, victim)) {
                        victim.dropItem(stack, true, false);
                        list.set(i, ItemStack.EMPTY);
                    }
                }
            }

            GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(victim.getWorld());
            if (gameWorldComponent.isInnocent(victim)) {
                GameTimeComponent.KEY.get(victim.getWorld()).addTime(GameConstants.TIME_ON_CIVILIAN_KILL);
            }

            TrainVoicePlugin.addPlayer(victim.getUuid());
            deathContext.markCompletedDefaultFlow();
        } finally {
            DeathApi.invokeAfterAttempt(deathContext);
        }
    }

    /**
     * 带有额外死亡回放数据的击杀入口。
     *
     * <p>这里的 {@code extraDeathData} 只会在“真正死亡成立”后才被并入 death 事件，
     * 因此扩展职业模组可以安全地把回放所需的补充字段塞进来，
     * 不会再出现“先记录了死亡文案，结果又被护盾或免死逻辑拦下”的假回放。</p>
     *
     * <p>一个典型用途是：
     * 某次死亡在玩法上并没有传统意义上的 killer，
     * 但回放仍希望显示“被谁的能力影响而死”。
     * 此时可把对应玩家 uuid 写进 {@code replay_actor}，
     * 由回放层决定是否把它当作第二个句子参数来展示。</p>
     */
    public static void killPlayer(PlayerEntity victim, boolean spawnBody, @Nullable PlayerEntity killer, Identifier deathReason, @Nullable NbtCompound extraDeathData) {
        if (extraDeathData == null || extraDeathData.isEmpty()) {
            killPlayer(victim, spawnBody, killer, deathReason);
            return;
        }

        PENDING_EXTRA_DEATH_DATA.set(extraDeathData.copy());
        try {
            killPlayer(victim, spawnBody, killer, deathReason);
        } finally {
            PENDING_EXTRA_DEATH_DATA.remove();
        }
    }

    public static boolean shouldDropOnDeath(@NotNull ItemStack stack, PlayerEntity victim) {
        return !stack.isEmpty() && (stack.isOf(WatheItems.REVOLVER) || ShouldDropOnDeath.EVENT.invoker().shouldDrop(stack, victim));
    }

    /**
     * Wathe 里“局内存活”的统一定义。
     *
     * <p>这里并不是单纯看玩家原版的 {@link PlayerEntity#isAlive()}，
     * 而是看玩家是否还以“局内可操作身份”留在游戏里。
     * 默认情况下，只要不是 spectator 且不是 creative，就视为仍然存活。</p>
     *
     * <p>现在额外支持一种扩展玩法授权：
     * 如果玩家被职业或调试命令通过 {@link PlayerLifeStateApi} 标记为
     * “creative / spectator 仍按玩法存活”，那么即使原版模式是旁观或创造，
     * 胜负、结算、心情、Tab/聊天限制、本能和职业名显示也都会继续走存活玩家分支。</p>
     *
     * <p>普通 OP 使用原版 /gamemode 切到 creative 或 spectator 不会获得这个授权；
     * 这类玩家仍然会被 Wathe 当作非存活处理。</p>
     */
    public static boolean isPlayerAliveAndSurvival(PlayerEntity player) {
        if (player == null) {
            return false;
        }
        if (!player.isSpectator() && !player.isCreative()) {
            return true;
        }
        return PlayerLifeStateApi.hasAliveOverride(player);
    }

    /**
     * Wathe 里“旁观/创造非存活视角”的统一入口。
     *
     * <p>许多客户端显示逻辑会用它决定是否展示全局旁观信息：
     * 职业名、阵营颜色、本能透视、聊天与玩家列表限制等。
     * 因此这里必须和 {@link #isPlayerAliveAndSurvival(PlayerEntity)} 保持互补，
     * 让特殊旁观 / 特殊创造仍然被隐藏成存活玩家视角。</p>
     */
    public static boolean isPlayerSpectatingOrCreative(PlayerEntity player) {
        return player != null && (player.isSpectator() || player.isCreative()) && !PlayerLifeStateApi.hasAliveOverride(player);
    }

    record BlockEntityInfo(NbtCompound nbt, ComponentMap components) {
    }

    record BlockInfo(BlockPos pos, BlockState state, @Nullable BlockEntityInfo blockEntityInfo) {
    }

    enum Mode {
        FORCE(true),
        MOVE(true),
        NORMAL(false);

        private final boolean allowsOverlap;

        Mode(final boolean allowsOverlap) {
            this.allowsOverlap = allowsOverlap;
        }

        public boolean allowsOverlap() {
            return this.allowsOverlap;
        }
    }

    // returns whether another reset should be attempted
    public static boolean tryResetTrain(ServerWorld serverWorld) {
        Identifier dimensionId = serverWorld.getRegistryKey().getValue();
            MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(serverWorld);
            BlockPos backupMinPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMinPos());
            BlockPos backupMaxPos = BlockPos.ofFloored(areas.getResetTemplateArea().getMaxPos());
            BlockBox backupTrainBox = BlockBox.create(backupMinPos, backupMaxPos);
            BlockPos trainMinPos = BlockPos.ofFloored(areas.getResetTemplateArea().offset(Vec3d.of(areas.getResetPasteOffset())).getMinPos());
            BlockPos trainMaxPos = trainMinPos.add(backupTrainBox.getDimensions());
            BlockBox trainBox = BlockBox.create(trainMinPos, trainMaxPos);

            Mode mode = Mode.FORCE;

            if (serverWorld.isRegionLoaded(backupMinPos, backupMaxPos) && serverWorld.isRegionLoaded(trainMinPos, trainMaxPos)) {
                List<BlockInfo> list = Lists.newArrayList();
                List<BlockInfo> list2 = Lists.newArrayList();
                List<BlockInfo> list3 = Lists.newArrayList();
                Deque<BlockPos> deque = Lists.newLinkedList();
                BlockPos blockPos5 = new BlockPos(
                        trainBox.getMinX() - backupTrainBox.getMinX(), trainBox.getMinY() - backupTrainBox.getMinY(), trainBox.getMinZ() - backupTrainBox.getMinZ()
                );

                for (int k = backupTrainBox.getMinZ(); k <= backupTrainBox.getMaxZ(); k++) {
                    for (int l = backupTrainBox.getMinY(); l <= backupTrainBox.getMaxY(); l++) {
                        for (int m = backupTrainBox.getMinX(); m <= backupTrainBox.getMaxX(); m++) {
                            BlockPos blockPos6 = new BlockPos(m, l, k);
                            BlockPos blockPos7 = blockPos6.add(blockPos5);
                            CachedBlockPosition cachedBlockPosition = new CachedBlockPosition(serverWorld, blockPos6, false);
                            BlockState blockState = cachedBlockPosition.getBlockState();

                            BlockEntity blockEntity = serverWorld.getBlockEntity(blockPos6);
                            if (blockEntity != null) {
                                BlockEntityInfo blockEntityInfo = new BlockEntityInfo(
                                        blockEntity.createComponentlessNbt(serverWorld.getRegistryManager()), blockEntity.getComponents()
                                );
                                list2.add(new BlockInfo(blockPos7, blockState, blockEntityInfo));
                                deque.addLast(blockPos6);
                            } else if (!blockState.isOpaqueFullCube(serverWorld, blockPos6) && !blockState.isFullCube(serverWorld, blockPos6)) {
                                list3.add(new BlockInfo(blockPos7, blockState, null));
                                deque.addFirst(blockPos6);
                            } else {
                                list.add(new BlockInfo(blockPos7, blockState, null));
                                deque.addLast(blockPos6);
                            }
                        }
                    }
                }

                List<BlockInfo> list4 = Lists.newArrayList();
                list4.addAll(list);
                list4.addAll(list2);
                list4.addAll(list3);
                List<BlockInfo> list5 = Lists.reverse(list4);

                for (BlockInfo blockInfo : list5) {
                    BlockEntity blockEntity3 = serverWorld.getBlockEntity(blockInfo.pos);
                    Clearable.clear(blockEntity3);
                    serverWorld.setBlockState(blockInfo.pos, Blocks.BARRIER.getDefaultState(), Block.NOTIFY_LISTENERS);
                }

                int mx = 0;

                for (BlockInfo blockInfo2 : list4) {
                    if (serverWorld.setBlockState(blockInfo2.pos, blockInfo2.state, Block.NOTIFY_LISTENERS)) {
                        mx++;
                    }
                }

                for (BlockInfo blockInfo2x : list2) {
                    BlockEntity blockEntity4 = serverWorld.getBlockEntity(blockInfo2x.pos);
                    if (blockInfo2x.blockEntityInfo != null && blockEntity4 != null) {
                        blockEntity4.readComponentlessNbt(blockInfo2x.blockEntityInfo.nbt, serverWorld.getRegistryManager());
                        blockEntity4.setComponents(blockInfo2x.blockEntityInfo.components);
                        blockEntity4.markDirty();
                    }

                    serverWorld.setBlockState(blockInfo2x.pos, blockInfo2x.state, Block.NOTIFY_LISTENERS);
                }

                for (BlockInfo blockInfo2x : list5) {
                    serverWorld.updateNeighbors(blockInfo2x.pos, blockInfo2x.state.getBlock());
                }

                serverWorld.getBlockTickScheduler().scheduleTicks(serverWorld.getBlockTickScheduler(), backupTrainBox, blockPos5);
                if (mx == 0) {
                    Wathe.LOGGER.info("Train reset failed: No blocks copied. Queueing another attempt. Dimension: {}", dimensionId);
                    return true;
                }
            } else {
                Wathe.LOGGER.info("Train reset failed: Clone positions not loaded. Queueing another attempt. Dimension: {}", dimensionId);
                return true;
            }

            // discard all player bodies and items
            for (PlayerBodyEntity body : serverWorld.getEntitiesByType(WatheEntities.PLAYER_BODY, playerBodyEntity -> true)) {
                body.discard();
            }
            for (ItemEntity item : serverWorld.getEntitiesByType(EntityType.ITEM, playerBodyEntity -> true)) {
                item.discard();
            }
            for (FirecrackerEntity entity : serverWorld.getEntitiesByType(WatheEntities.FIRECRACKER, entity -> true))
                entity.discard();
            for (NoteEntity entity : serverWorld.getEntitiesByType(WatheEntities.NOTE, entity -> true))
                entity.discard();

            Wathe.LOGGER.info("Train reset successful. Dimension: {}", dimensionId);
            return false;
    }

    /**
     * 把玩家的原版重生点设置到当前地图维度。
     *
     * <p>这主要服务于投票切图后的晚加入玩家和意外重生场景，避免他们回到主世界。</p>
     */
    public static void setPlayerSpawnToMapSpawn(ServerPlayerEntity player, ServerWorld world) {
        MapVariablesWorldComponent mapVariables = MapVariablesWorldComponent.KEY.get(world);
        MapVariablesWorldComponent.PosWithOrientation spawnPos = player.isSpectator()
                ? mapVariables.getSpectatorSpawnPos()
                : mapVariables.getSpawnPos();

        player.setSpawnPoint(
                world.getRegistryKey(),
                BlockPos.ofFloored(spawnPos.pos),
                spawnPos.yaw,
                true,
                false
        );
    }

    public static void teleportPlayer(ServerPlayerEntity player) {
        if (player.getServer() == null) {
            return;
        }

        MapVotingComponent voting = MapVotingComponent.KEY.get(player.getServer().getScoreboard());
        Identifier targetDimensionId = voting.getLastSelectedDimension();
        if (targetDimensionId == null) {
            return;
        }

        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, targetDimensionId);
        ServerWorld targetWorld = player.getServer().getWorld(worldKey);
        if (targetWorld == null) {
            Wathe.LOGGER.warn("Cannot teleport player {}: selected Wathe map dimension {} is missing",
                    player.getGameProfile().getName(), targetDimensionId);
            return;
        }

        teleportPlayerToMapSpawn(player, targetWorld);
    }

    /**
     * 投票结束后把全服玩家迁移到选中的地图维度。
     *
     * <p>这里遍历所有世界，而不是只迁移主世界玩家；否则玩家在别的维度时会漏传，
     * 下一局准备区、指令目标和结算组件都会落到不同世界。</p>
     */
    public static void finalizeVoting(ServerWorld currentWorld, Identifier targetDimensionId) {
        RegistryKey<World> dimKey = RegistryKey.of(RegistryKeys.WORLD, targetDimensionId);
        ServerWorld targetWorld = currentWorld.getServer().getWorld(dimKey);

        if (targetWorld == null) {
            Wathe.LOGGER.warn("Target Wathe map dimension {} not found, map voting result ignored", targetDimensionId);
            return;
        }

        for (ServerWorld world : currentWorld.getServer().getWorlds()) {
            for (ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
                if (world.getRegistryKey().equals(dimKey)) {
                    /*
                     * 如果投票结果还是当前维度，不移动玩家位置。
                     * 只刷新重生点，避免“继续玩本地图”时把准备区玩家又拉回出生点。
                     */
                    setPlayerSpawnToMapSpawn(player, targetWorld);
                } else {
                    teleportPlayerToMapSpawn(player, targetWorld);
                }
            }
        }

        Wathe.LOGGER.info("Wathe map voting selected dimension {}", targetDimensionId);
    }

    private static void teleportPlayerToMapSpawn(ServerPlayerEntity player, ServerWorld targetWorld) {
        MapVariablesWorldComponent mapVariables = MapVariablesWorldComponent.KEY.get(targetWorld);
        MapVariablesWorldComponent.PosWithOrientation spawnPos = player.isSpectator()
                ? mapVariables.getSpectatorSpawnPos()
                : mapVariables.getSpawnPos();

        BlockPos chunkPos = BlockPos.ofFloored(spawnPos.pos);
        targetWorld.getChunk(chunkPos);
        player.teleport(
                targetWorld,
                spawnPos.pos.getX() + 0.5,
                spawnPos.pos.getY() + 1,
                spawnPos.pos.getZ() + 0.5,
                spawnPos.yaw,
                spawnPos.pitch
        );
        setPlayerSpawnToMapSpawn(player, targetWorld);
        player.getInventory().clear();
        TrainVoicePlugin.resetPlayer(player.getUuid());
    }

    public static int getReadyPlayerCount(World world) {
        List<? extends PlayerEntity> players = world.getPlayers();
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(world);
        return Math.toIntExact(players.stream().filter(p -> isPlayerInReadyArea(p, areas)).count());
    }

    public enum WinStatus {
        NONE, KILLERS, PASSENGERS, TIME, LOOSE_END
    }
}
