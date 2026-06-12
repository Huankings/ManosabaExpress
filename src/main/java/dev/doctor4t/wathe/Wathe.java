package dev.doctor4t.wathe;

import com.google.common.reflect.Reflection;
import dev.doctor4t.wathe.block.DoorPartBlock;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import dev.doctor4t.wathe.command.*;
import dev.doctor4t.wathe.command.argument.GameModeArgumentType;
import dev.doctor4t.wathe.command.argument.MapEffectArgumentType;
import dev.doctor4t.wathe.command.argument.TimeOfDayArgumentType;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfigurationReloader;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.*;
import dev.doctor4t.wathe.record.GameRecordHooks;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.record.GameRecordTypes;
import dev.doctor4t.wathe.record.replay.DefaultReplayFormatters;
import dev.doctor4t.wathe.record.replay.ReplayGenerator;
import dev.doctor4t.wathe.record.replay.ReplayRegistry;
import dev.doctor4t.wathe.task.TaskPointSyncManager;
import dev.doctor4t.wathe.util.*;
import dev.upcraft.datasync.api.DataSyncAPI;
import dev.upcraft.datasync.api.util.Entitlements;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.serialize.ConstantArgumentSerializer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class Wathe implements ModInitializer {
    public static final String MOD_ID = "wathe";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static @NotNull Identifier id(String name) {
        return Identifier.of(MOD_ID, name);
    }

    @Override
    public void onInitialize() {
        // Init constants
        GameConstants.init();

        // 读取 data/wathe/maps/*.json，供地图投票和地图增强配置使用。
        MapEnhancementsConfigurationReloader.register();

        // Registry initializers
        Reflection.initialize(WatheDataComponentTypes.class);
        WatheSounds.initialize();
        WatheEntities.initialize();
        WatheBlocks.initialize();
        WatheItems.initialize();
        WatheBlockEntities.initialize();
        WatheParticles.initialize();

        // Register command argument types
        ArgumentTypeRegistry.registerArgumentType(id("timeofday"), TimeOfDayArgumentType.class, ConstantArgumentSerializer.of(TimeOfDayArgumentType::timeofday));
        ArgumentTypeRegistry.registerArgumentType(id("gamemode"), GameModeArgumentType.class, ConstantArgumentSerializer.of(GameModeArgumentType::gameMode));
        ArgumentTypeRegistry.registerArgumentType(id("mapeffect"), MapEffectArgumentType.class, ConstantArgumentSerializer.of(MapEffectArgumentType::mapEffect));

        // Register commands
        CommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess, environment) -> {
            MapVariablesCommand.register(dispatcher);
            GameSettingsCommand.register(dispatcher);
            GiveRoomKeyCommand.register(dispatcher);
            StartCommand.register(dispatcher);
            StopCommand.register(dispatcher);
            SetVisualCommand.register(dispatcher);
            ForceRoleCommand.register(dispatcher);
//            UpdateDoorsCommand.register(dispatcher);
            SetTimerCommand.register(dispatcher);
            SetMoneyCommand.register(dispatcher);
            LockToSupportersCommand.register(dispatcher);
            SetKillerCountCommand.register(dispatcher);
            SetGameModeCommand.register(dispatcher);
            SetGradualResetCommand.register(dispatcher);
            MoodEffectDeathCommand.register(dispatcher);
            TaskPointCommand.register(dispatcher);
            AllowJumpCommand.register(dispatcher);
            PlayerCollisionCommand.register(dispatcher);
            InstinctCommand.register(dispatcher);
            MapVoteCommand.register(dispatcher);
        }));

        /*
         * 保留原版支持者检测逻辑。
         * 如果对局已经开始，局中新加入的玩家也会在这里留下“玩家加入”事件。
         */
        ServerPlayerEvents.JOIN.register(player -> {
            DataSyncAPI.refreshAllPlayerData(player.getUuid()).thenRunAsync(() -> {
                if (GameWorldComponent.KEY.get(player.getWorld()).isLockedToSupporters() && !Wathe.isSupporter(player)) {
                    player.networkHandler.disconnect(Text.literal("Server is reserved to doctor4t supporters."));
                }
            }, player.getWorld().getServer());

            if (GameRecordManager.hasActiveMatch()) {
                GameRecordManager.recordPlayerJoin(player);
            }

            MapVotingComponent voting = MapVotingComponent.KEY.get(player.getServer().getScoreboard());
            voting.onPlayerJoin();
            ServerWorld selectedWorld = voting.getLastSelectedWorld();
            if (!voting.isVotingActive()
                    && selectedWorld != null
                    && !selectedWorld.getRegistryKey().equals(player.getWorld().getRegistryKey())) {
                /*
                 * 投票已经选定地图后，晚加入玩家可能仍出生在主世界。
                 * 这里把他拉回当前地图维度，避免下一次 /start 或旁观视角落到错误世界。
                 */
                GameFunctions.teleportPlayer(player);
            } else if (selectedWorld != null && selectedWorld.getRegistryKey().equals(player.getWorld().getRegistryKey())) {
                GameFunctions.setPlayerSpawnToMapSpawn(player, selectedWorld);
            }
        });

        PayloadTypeRegistry.playS2C().register(ShootMuzzleS2CPayload.ID, ShootMuzzleS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PoisonUtils.PoisonOverlayPayload.ID, PoisonUtils.PoisonOverlayPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GunDropPayload.ID, GunDropPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TaskCompletePayload.ID, TaskCompletePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AnnounceWelcomePayload.ID, AnnounceWelcomePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AnnounceEndingPayload.ID, AnnounceEndingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TaskPointSyncPayload.ID, TaskPointSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(KnifeStabPayload.ID, KnifeStabPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GunShootPayload.ID, GunShootPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GrenadeThrowModePayload.ID, GrenadeThrowModePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StoreBuyPayload.ID, StoreBuyPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(NoteEditPayload.ID, NoteEditPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapVotePayload.ID, MapVotePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(KnifeStabPayload.ID, new KnifeStabPayload.Receiver());
        ServerPlayNetworking.registerGlobalReceiver(GunShootPayload.ID, new GunShootPayload.Receiver());
        ServerPlayNetworking.registerGlobalReceiver(GrenadeThrowModePayload.ID, new GrenadeThrowModePayload.Receiver());
        ServerPlayNetworking.registerGlobalReceiver(StoreBuyPayload.ID, new StoreBuyPayload.Receiver());
        ServerPlayNetworking.registerGlobalReceiver(NoteEditPayload.ID, new NoteEditPayload.Receiver());
        ServerPlayNetworking.registerGlobalReceiver(MapVotePayload.ID, new MapVotePayload.Receiver());

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient || player.isCreative()) {
                return ActionResult.PASS;
            }

            /*
             * 地图数据包可以声明某些方块禁止交互，例如装饰按钮、展示容器等。
             * 只在非创造玩家身上生效，方便管理员仍能现场调试地图。
             */
            MapEnhancementsWorldComponent enhancements = MapEnhancementsWorldComponent.KEY.get(world);
            if (enhancements.getInteractionBlacklistConfig().isBlacklisted(world.getBlockState(hitResult.getBlockPos()).getBlock())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // 注册原版“塞床物品”到统一床效果接口。
        WatheBedEffects.register();

        /*
         * 回放系统分为两层：
         * 1. GameRecordHooks / GameRecordManager 负责采集和封存事件；
         * 2. ReplayRegistry / DefaultReplayFormatters 负责把事件翻译成聊天文本。
         *
         * 先注册本体默认格式化器，后续扩展职业模组可继续追加自己的格式化器。
         */
        registerReplayFormatters();
        GameRecordHooks.register();

        /*
         * 玩家离线事件要纳入时间线；重生则不算“重新加入”，避免切旁观时刷出伪加入记录。
         */
        ServerPlayerEvents.LEAVE.register(GameRecordManager::recordPlayerLeave);

        /*
         * 一局对局记录真正封存后，再完整播放一次整局事件。
         * 局内实时播报则由 GameRecordManager.addEvent 在记录时即时发送。
         */
        dev.doctor4t.wathe.api.event.RecordEvents.ON_RECORD_END.register(ReplayGenerator::generateAndSend);

        // 初始化任务点扫描 / 同步系统。
        TaskPointSyncManager.initialize();

        Scheduler.init();
    }

    private static void registerReplayFormatters() {
        ReplayRegistry.registerFormatter(GameRecordTypes.SHOP_PURCHASE, DefaultReplayFormatters::formatShopPurchase);
        ReplayRegistry.registerFormatter(GameRecordTypes.ITEM_PICKUP, DefaultReplayFormatters::formatItemPickup);
        ReplayRegistry.registerFormatter(GameRecordTypes.ITEM_USE, DefaultReplayFormatters::formatItemUse);
        ReplayRegistry.registerFormatter(GameRecordTypes.ITEM_HIT, DefaultReplayFormatters::formatItemHit);
        ReplayRegistry.registerFormatter(GameRecordTypes.PLATTER_TAKE, DefaultReplayFormatters::formatPlatterTake);
        ReplayRegistry.registerFormatter(GameRecordTypes.CONSUME_ITEM, DefaultReplayFormatters::formatConsumeItem);
        ReplayRegistry.registerFormatter(GameRecordTypes.PLAYER_POISONED, DefaultReplayFormatters::formatPoisoned);
        ReplayRegistry.registerFormatter(GameRecordTypes.DEATH, DefaultReplayFormatters::formatDeath);
        ReplayRegistry.registerFormatter(GameRecordTypes.SHIELD_BLOCKED, DefaultReplayFormatters::formatShieldBlocked);
        ReplayRegistry.registerFormatter(GameRecordTypes.SKILL_USE, DefaultReplayFormatters::formatSkillUse);
        ReplayRegistry.registerFormatter(GameRecordTypes.GLOBAL_EVENT, DefaultReplayFormatters::formatGlobalEvent);
        ReplayRegistry.registerFormatter(GameRecordTypes.DOOR_INTERACTION, DefaultReplayFormatters::formatDoorInteraction);
        ReplayRegistry.registerFormatter(GameRecordTypes.ROLE_CHANGED, DefaultReplayFormatters::formatRoleChanged);
        ReplayRegistry.registerFormatter(GameRecordTypes.TASK_COMPLETE, DefaultReplayFormatters::formatTaskComplete);

        /*
         * 本体内置物品 / 全局事件格式化器。
         * 扩展职业模组只要注册同类 formatter，就能把自己的事件接进同一套回放系统。
         */
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.GRENADE), DefaultReplayFormatters::formatGrenadeUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.CROWBAR), DefaultReplayFormatters::formatCrowbarUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.LOCKPICK), DefaultReplayFormatters::formatLockpickUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.BODY_BAG), DefaultReplayFormatters::formatBodyBagUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.NOTE), DefaultReplayFormatters::formatNoteUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.FIRECRACKER), DefaultReplayFormatters::formatFirecrackerUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.POISON_VIAL), DefaultReplayFormatters::formatPoisonVialUse);
        ReplayRegistry.registerItemUseFormatter(Registries.ITEM.getId(WatheItems.SCORPION), DefaultReplayFormatters::formatScorpionUse);

        ReplayRegistry.registerItemHitFormatter(Registries.ITEM.getId(WatheItems.KNIFE), DefaultReplayFormatters::formatKnifeHit);
        ReplayRegistry.registerItemHitFormatter(Registries.ITEM.getId(WatheItems.REVOLVER), DefaultReplayFormatters::formatGunHit);
        ReplayRegistry.registerItemHitFormatter(Registries.ITEM.getId(WatheItems.DERRINGER), DefaultReplayFormatters::formatGunHit);
        ReplayRegistry.registerItemHitFormatter(Registries.ITEM.getId(WatheItems.BAT), DefaultReplayFormatters::formatBatHit);
        ReplayRegistry.registerItemHitTypeFormatter(GameConstants.DeathReasons.KNIFE, DefaultReplayFormatters::formatKnifeHit);
        ReplayRegistry.registerItemHitTypeFormatter(GameConstants.DeathReasons.GUN, DefaultReplayFormatters::formatGunHit);
        ReplayRegistry.registerItemHitTypeFormatter(GameConstants.DeathReasons.BAT, DefaultReplayFormatters::formatBatHit);

        ReplayRegistry.registerGlobalEventFormatter(Wathe.id("psycho_mode_end"), DefaultReplayFormatters::formatPsychoModeEnd);
        ReplayRegistry.registerGlobalEventFormatter(Wathe.id("blackout_recovering"), DefaultReplayFormatters::formatBlackoutRecovering);
        ReplayRegistry.registerGlobalEventFormatter(Wathe.id("blackout_restored"), DefaultReplayFormatters::formatBlackoutRestored);
        ReplayRegistry.registerGlobalEventFormatter(Wathe.id("fishing_rod_used"), DefaultReplayFormatters::formatFishingRodUsed);
        ReplayRegistry.registerGlobalEventFormatter(Wathe.id("scorpion_sting"), DefaultReplayFormatters::formatScorpionSting);
    }

    public static boolean isSkyVisibleAdjacent(@NotNull Entity player) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos playerPos = BlockPos.ofFloored(player.getEyePos());
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                mutable.set(playerPos.getX() + x, playerPos.getY(), playerPos.getZ() + z);
                if (player.getWorld().isSkyVisible(mutable)) {
                    return !(player.getWorld().getBlockState(playerPos).getBlock() instanceof DoorPartBlock);
                }
            }
        }
        return false;
    }

    public static boolean isExposedToWind(@NotNull Entity player) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockPos playerPos = BlockPos.ofFloored(player.getEyePos());
        for (int x = 0; x <= 10; x++) {
            mutable.set(playerPos.getX() - x, player.getEyePos().getY(), playerPos.getZ());
            if (!player.getWorld().isSkyVisible(mutable)) {
                return false;
            }
        }
        return true;
    }

    public static final Identifier COMMAND_ACCESS = id("commandaccess");

    public static int executeSupporterCommand(ServerCommandSource source, Runnable runnable) {
        /*
         * 这里原本是“赞助者专用指令”的统一闸门：
         * Start / SetTimer / SetMoney / GameSettings / MapVariables 等命令虽然已经要求 OP 权限，
         * 但执行时仍会再次检查玩家是否拥有 supporter entitlement，
         * 所以在正式服导出的 jar 里，管理员依然会被这层二次限制拦住。
         *
         * 现在为了方便本地联调和服务器实测，直接把这层 supporter 校验注销掉，
         * 改为：只要命令已经通过各自的 Brigadier `.requires(source -> source.hasPermissionLevel(2))`
         * 注册条件，就允许继续执行。这样控制台和管理员玩家都能正常测试这些命令。
         *
         * 注意：
         * 1. 这里只解除“指令层”的 supporter 限制，不影响其他仍然使用 isSupporter 的展示/皮肤逻辑；
         * 2. 命令本身的管理员权限判断仍然保留，所以普通玩家不会因为这里放开就获得测试指令权限。
         */
        runnable.run();
        return 1;
    }

    public static @NotNull Boolean isSupporter(PlayerEntity player) {
        Optional<Entitlements> entitlements = Entitlements.token().get(player.getUuid());
        return entitlements.map(value -> value.keys().stream().anyMatch(identifier -> identifier.equals(COMMAND_ACCESS))).orElse(false);
    }
}
