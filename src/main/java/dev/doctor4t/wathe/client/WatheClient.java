package dev.doctor4t.wathe.client;

import com.google.common.collect.Maps;
import dev.doctor4t.ratatouille.client.util.OptionLocker;
import dev.doctor4t.ratatouille.client.util.ambience.AmbienceUtil;
import dev.doctor4t.ratatouille.client.util.ambience.BackgroundAmbience;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.WatheConfig;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerGrenadeComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.TrainWorldComponent;
import dev.doctor4t.wathe.client.gui.RoundTextRenderer;
import dev.doctor4t.wathe.client.gui.StoreRenderer;
import dev.doctor4t.wathe.client.gui.TimeRenderer;
import dev.doctor4t.wathe.client.task.TaskPointClientState;
import dev.doctor4t.wathe.client.task.TaskPointOverlayRenderer;
import dev.doctor4t.wathe.client.model.WatheModelLayers;
import dev.doctor4t.wathe.client.model.item.KnifeModelLoadingPlugin;
import dev.doctor4t.wathe.client.render.block_entity.PlateBlockEntityRenderer;
import dev.doctor4t.wathe.client.render.block_entity.SmallDoorBlockEntityRenderer;
import dev.doctor4t.wathe.client.render.block_entity.WheelBlockEntityRenderer;
import dev.doctor4t.wathe.client.render.entity.FirecrackerEntityRenderer;
import dev.doctor4t.wathe.client.render.entity.HornBlockEntityRenderer;
import dev.doctor4t.wathe.client.render.entity.NoteEntityRenderer;
import dev.doctor4t.wathe.client.util.WatheItemTooltips;
import dev.doctor4t.wathe.entity.FirecrackerEntity;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.*;
import dev.doctor4t.wathe.util.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.entity.EmptyEntityRenderer;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class WatheClient implements ClientModInitializer {
    private static float soundLevel = 0f;
    public static HandParticleManager handParticleManager;
    public static Map<PlayerEntity, Vec3d> particleMap;
    private static boolean prevGameRunning;
    public static GameWorldComponent gameComponent;
    public static TrainWorldComponent trainComponent;
    public static PlayerMoodComponent moodComponent;

    public static final Map<UUID, PlayerListEntry> PLAYER_ENTRIES_CACHE = Maps.newHashMap();

    public static KeyBinding instinctKeybind;
    public static KeyBinding taskPointKeybind;
    public static float prevInstinctLightLevel = -.04f;
    public static float instinctLightLevel = -.04f;
    private static int lastGrenadeSelectedSlot = -1;
    private static boolean grenadeThrowModeToggleHeld = false;

    public static boolean shouldDisableHudAndDebug() {
        MinecraftClient client = MinecraftClient.getInstance();
        return (client == null || (client.player != null && !client.player.isCreative() && !client.player.isSpectator()));
    }

    @Override
    public void onInitializeClient() {
        // Load config
        WatheConfig.init(Wathe.MOD_ID, WatheConfig.class);

        // Initialize ScreenParticle
        handParticleManager = new HandParticleManager();
        particleMap = new HashMap<>();

        // Register particle factories
        WatheParticles.registerFactories();

        // Entity renderer registration
        EntityRendererRegistry.register(WatheEntities.SEAT, EmptyEntityRenderer::new);
        EntityRendererRegistry.register(WatheEntities.FIRECRACKER, FirecrackerEntityRenderer::new);
        EntityRendererRegistry.register(WatheEntities.GRENADE, FlyingItemEntityRenderer::new);
        EntityRendererRegistry.register(WatheEntities.NOTE, NoteEntityRenderer::new);

        // Register entity model layers
        WatheModelLayers.initialize();

        // Custom Baked Models
        ModelLoadingPlugin.register(new KnifeModelLoadingPlugin());

        // Block render layers
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getCutout(),
                WatheBlocks.STAINLESS_STEEL_VENT_HATCH,
                WatheBlocks.DARK_STEEL_VENT_HATCH,
                WatheBlocks.TARNISHED_GOLD_VENT_HATCH,
                WatheBlocks.METAL_SHEET_WALKWAY,
                WatheBlocks.STAINLESS_STEEL_LADDER,
                WatheBlocks.COCKPIT_DOOR,
                WatheBlocks.METAL_SHEET_DOOR,
                WatheBlocks.GOLDEN_GLASS_PANEL,
                WatheBlocks.CULLING_GLASS,
                WatheBlocks.STAINLESS_STEEL_WALKWAY,
                WatheBlocks.DARK_STEEL_WALKWAY,
                WatheBlocks.PANEL_STRIPES,
                WatheBlocks.RAIL_BEAM,
                WatheBlocks.TRIMMED_RAILING_POST,
                WatheBlocks.DIAGONAL_TRIMMED_RAILING,
                WatheBlocks.TRIMMED_RAILING,
                WatheBlocks.TRIMMED_EBONY_STAIRS,
                WatheBlocks.WHITE_LOUNGE_COUCH,
                WatheBlocks.WHITE_OTTOMAN,
                WatheBlocks.WHITE_TRIMMED_BED,
                WatheBlocks.BLUE_LOUNGE_COUCH,
                WatheBlocks.GREEN_LOUNGE_COUCH,
                WatheBlocks.BAR_STOOL,
                WatheBlocks.WALL_LAMP,
                WatheBlocks.SMALL_BUTTON,
                WatheBlocks.ELEVATOR_BUTTON,
                WatheBlocks.STAINLESS_STEEL_SPRINKLER,
                WatheBlocks.GOLD_SPRINKLER,
                WatheBlocks.GOLD_ORNAMENT,
                WatheBlocks.WHEEL,
                WatheBlocks.RUSTED_WHEEL,
                WatheBlocks.BARRIER_PANEL,
                WatheBlocks.FOOD_PLATTER,
                WatheBlocks.DRINK_TRAY,
                WatheBlocks.LIGHT_BARRIER,
                WatheBlocks.HORN
        );
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getTranslucent(),
                WatheBlocks.RHOMBUS_GLASS,
                WatheBlocks.PRIVACY_GLASS_PANEL,
                WatheBlocks.CULLING_BLACK_HULL,
                WatheBlocks.CULLING_WHITE_HULL,
                WatheBlocks.HULL_GLASS,
                WatheBlocks.RHOMBUS_HULL_GLASS
        );

        // Custom block models
        CustomModelProvider customModelProvider = new CustomModelProvider();
        ModelLoadingPlugin.register(customModelProvider);

        // Block Entity Renderers
        BlockEntityRendererFactories.register(
                WatheBlockEntities.SMALL_GLASS_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/small_glass_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.SMALL_WOOD_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/small_wood_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.ANTHRACITE_STEEL_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/anthracite_steel_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.KHAKI_STEEL_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/khaki_steel_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.MAROON_STEEL_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/maroon_steel_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.MUNTZ_STEEL_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/muntz_steel_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.NAVY_STEEL_DOOR,
                ctx -> new SmallDoorBlockEntityRenderer(Wathe.id("textures/entity/navy_steel_door.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.WHEEL,
                ctx -> new WheelBlockEntityRenderer(Wathe.id("textures/entity/wheel.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.RUSTED_WHEEL,
                ctx -> new WheelBlockEntityRenderer(Wathe.id("textures/entity/rusted_wheel.png"), ctx)
        );
        BlockEntityRendererFactories.register(
                WatheBlockEntities.BEVERAGE_PLATE,
                PlateBlockEntityRenderer::new
        );
        BlockEntityRendererFactories.register(WatheBlockEntities.HORN, HornBlockEntityRenderer::new);

        // Ambience
        AmbienceUtil.registerBackgroundAmbience(new BackgroundAmbience(WatheSounds.AMBIENT_TRAIN_INSIDE, player -> isTrainMoving() && !Wathe.isSkyVisibleAdjacent(player), 20));
        AmbienceUtil.registerBackgroundAmbience(new BackgroundAmbience(WatheSounds.AMBIENT_TRAIN_OUTSIDE, player -> isTrainMoving() && Wathe.isSkyVisibleAdjacent(player), 20));
        AmbienceUtil.registerBackgroundAmbience(new BackgroundAmbience(WatheSounds.AMBIENT_PSYCHO_DRONE, player -> gameComponent.isPsychoActive(), 20));
//        AmbienceUtil.registerBlockEntityAmbience(WatheBlockEntities.SPRINKLER, new BlockEntityAmbience(WatheSounds.BLOCK_SPRINKLER_RUN, 0.5f, blockEntity -> blockEntity instanceof SprinklerBlockEntity sprinklerBlockEntity && sprinklerBlockEntity.isPowered(), 20));

        // Caching components
        ClientTickEvents.START_WORLD_TICK.register(clientWorld -> {
            gameComponent = GameWorldComponent.KEY.get(clientWorld);
            trainComponent = TrainWorldComponent.KEY.get(clientWorld);
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            moodComponent = player == null ? null : PlayerMoodComponent.KEY.get(player);
        });

        // Lock options
        OptionLocker.overrideOption("gamma", 0d);
        OptionLocker.overrideOption("renderDistance", getLockedRenderDistance(WatheConfig.ultraPerfMode)); // mfw 15 fps on a 3050 - Cup // haha 🫵 brokie - RAT // buy me a better one then - Cup // okay nvm I fixed it I was actually rendering a lot of empty chunks we didn't need my bad LMAO - RAT
        OptionLocker.overrideOption("showSubtitles", false);
        OptionLocker.overrideOption("autoJump", false);
        OptionLocker.overrideOption("renderClouds", CloudRenderMode.OFF);
        OptionLocker.overrideSoundCategoryVolume("music", 0.0);
        OptionLocker.overrideSoundCategoryVolume("record", 0.1);
        OptionLocker.overrideSoundCategoryVolume("weather", 1.0);
        OptionLocker.overrideSoundCategoryVolume("block", 1.0);
        OptionLocker.overrideSoundCategoryVolume("hostile", 1.0);
        OptionLocker.overrideSoundCategoryVolume("neutral", 1.0);
        OptionLocker.overrideSoundCategoryVolume("player", 1.0);
        OptionLocker.overrideSoundCategoryVolume("ambient", 1.0);
        OptionLocker.overrideSoundCategoryVolume("voice", 1.0);


        // Item tooltips
        WatheItemTooltips.addTooltips();

        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            /*
             * 左键切换手雷模式，而不是执行原本的攻击动作。
             *
             * 这里使用客户端“预攻击”回调的原因是：
             * 1. 可以在攻击真正发生前直接拦截；
             * 2. 不会误打玩家、误打方块；
             * 3. 只在主手拿着手雷时生效，其它物品完全不受影响。
             */
            if (!player.getMainHandStack().isOf(WatheItems.GRENADE)) {
                return false;
            }

            /*
             * Fabric 的预攻击回调在“左键持续按住”时会重复触发，
             * 如果不做额外限流，就会出现：
             * 1. 点一下左键，因为按下时间稍长而连续切换两次；
             * 2. 长按左键时，模式在直投 / 蓄力之间疯狂来回跳。
             *
             * 这里加一个“本次按住是否已经处理过”的锁：
             * 1. 同一次按住左键，只允许切换一次；
             * 2. 必须先松开左键，下一次重新按下才允许再次切换；
             * 3. 在锁未释放前，后续重复回调仍继续返回 true，保证攻击动作也会被吞掉。
             */
            if (grenadeThrowModeToggleHeld) {
                return true;
            }
            grenadeThrowModeToggleHeld = true;

            PlayerGrenadeComponent component = PlayerGrenadeComponent.KEY.get(player);
            component.toggleLocal();
            ClientPlayNetworking.send(new GrenadeThrowModePayload(component.isDirectThrowMode()));
            showGrenadeThrowModeSwitchMessage(player);
            return true;
        });

        ClientTickEvents.START_WORLD_TICK.register(clientWorld -> {
            prevInstinctLightLevel = instinctLightLevel;
            // instinct night vision
            if (WatheClient.isInstinctEnabled()) {
                instinctLightLevel += .1f;
            } else {
                instinctLightLevel -= .1f;
            }
            instinctLightLevel = MathHelper.clamp(instinctLightLevel, -.04f, .5f);

            // Cache player entries
            for (AbstractClientPlayerEntity player : clientWorld.getPlayers()) {
                ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
                if (networkHandler != null) {
                    PLAYER_ENTRIES_CACHE.put(player.getUuid(), networkHandler.getPlayerListEntry(player.getUuid()));
                }
            }
            if (!prevGameRunning && gameComponent.isRunning()) {
                MinecraftClient.getInstance().player.getInventory().selectedSlot = 8;
            }
            prevGameRunning = gameComponent.isRunning();

            // Fade sound with game start / stop fade
            GameWorldComponent component = GameWorldComponent.KEY.get(clientWorld);
            if (component.getFade() > 0) {
                MinecraftClient.getInstance().getSoundManager().updateSoundVolume(SoundCategory.MASTER, MathHelper.map(component.getFade(), 0, GameConstants.FADE_TIME, soundLevel, 0));
            } else {
                MinecraftClient.getInstance().getSoundManager().updateSoundVolume(SoundCategory.MASTER, soundLevel);
                soundLevel = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.MASTER);
            }

            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) {
                maybeShowGrenadeThrowModeHint(player);
                StoreRenderer.tick();
                TimeRenderer.tick();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register((client) -> {
            WatheClient.handParticleManager.tick();
            RoundTextRenderer.tick();

            /*
             * 左键松开后再解除“本次按住已处理”的锁。
             *
             * 这样就能实现：
             * - 按住期间无论回调触发多少次，都只切一次；
             * - 松开后下一次点击，才允许再次切换。
             */
            if (!client.options.attackKey.isPressed()) {
                grenadeThrowModeToggleHeld = false;
            }

            while (taskPointKeybind != null && taskPointKeybind.wasPressed() && client.player != null) {
                boolean enabled = TaskPointClientState.toggleTaskPointOverlayEnabled();
                client.player.sendMessage(
                        Text.translatable(enabled ? "hud.task_point.toggle.enabled" : "hud.task_point.toggle.disabled")
                                .formatted(enabled ? Formatting.GREEN : Formatting.RED),
                        true
                );
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ShootMuzzleS2CPayload.ID, new ShootMuzzleS2CPayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(PoisonUtils.PoisonOverlayPayload.ID, new PoisonUtils.PoisonOverlayPayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(GunDropPayload.ID, new GunDropPayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(AnnounceWelcomePayload.ID, new AnnounceWelcomePayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(AnnounceEndingPayload.ID, new AnnounceEndingPayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(TaskCompletePayload.ID, new TaskCompletePayload.Receiver());
        ClientPlayNetworking.registerGlobalReceiver(TaskPointSyncPayload.ID, new TaskPointSyncPayload.Receiver());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TaskPointClientState.clear());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TaskPointOverlayRenderer::render);

        // Instinct keybind
        instinctKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + Wathe.MOD_ID + ".instinct",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                "category." + Wathe.MOD_ID + ".keybinds"
        ));

        // 任务点透视开关键：按一下切换开/关，而不是像本能那样长按生效。
        taskPointKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + Wathe.MOD_ID + ".task_points",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category." + Wathe.MOD_ID + ".keybinds"
        ));
    }

    public static TrainWorldComponent getTrainComponent() {
        return trainComponent;
    }

    public static float getTrainSpeed() {
        return trainComponent.getSpeed();
    }

    public static boolean isTrainMoving() {
        return trainComponent != null && trainComponent.getSpeed() > 0;
    }

    public static class CustomModelProvider implements ModelLoadingPlugin {

        private final Map<Identifier, UnbakedModel> modelIdToBlock = new Object2ObjectOpenHashMap<>();
        private final Set<Identifier> withInventoryVariant = new HashSet<>();

        public void register(Block block, UnbakedModel model) {
            this.register(Registries.BLOCK.getId(block), model);
        }

        public void register(Identifier id, UnbakedModel model) {
            this.modelIdToBlock.put(id, model);
        }

        public void markInventoryVariant(Block block) {
            this.markInventoryVariant(Registries.BLOCK.getId(block));
        }

        public void markInventoryVariant(Identifier id) {
            this.withInventoryVariant.add(id);
        }

        @Override
        public void onInitializeModelLoader(Context ctx) {
            ctx.modifyModelOnLoad().register((model, context) -> {
                ModelIdentifier topLevelId = context.topLevelId();
                if (topLevelId == null) {
                    return model;
                }
                Identifier id = topLevelId.id();
                if (topLevelId.getVariant().equals("inventory") && !this.withInventoryVariant.contains(id)) {
                    return model;
                }
                if (this.modelIdToBlock.containsKey(id)) {
                    return this.modelIdToBlock.get(id);
                }
                return model;
            });
        }
    }

    public static boolean isPlayerAliveAndInSurvival() {
        return GameFunctions.isPlayerAliveAndSurvival(MinecraftClient.getInstance().player);
    }

    public static boolean isPlayerSpectatingOrCreative() {
        return GameFunctions.isPlayerSpectatingOrCreative(MinecraftClient.getInstance().player);
    }

    public static boolean isKiller() {
        return gameComponent != null && gameComponent.canUseKillerFeatures(MinecraftClient.getInstance().player);
    }

    /**
     * 按“角色表里登记的真实职业”取本能透视颜色。
     *
     * <p>这里故意不复用活人那套“杀手红 / 心情色 / 旁观白”的显示逻辑，
     * 因为尸体需要表达的是“死者到底是什么职业”，而不是活着时给本能看的
     * 那套玩法语义。这样原版职业和后续通过 {@code WatheRoles.registerRole(...)}
     * 注册的扩展职业，都会自动继承各自的职业颜色。</p>
     *
     * @param playerUuid 尸体绑定的原玩家 UUID
     * @return 对应职业颜色；如果当前客户端还拿不到该玩家职业，则返回 -1 表示不高亮
     */
    public static int getInstinctRoleHighlight(UUID playerUuid) {
        if (gameComponent == null) return -1;

        Role role = gameComponent.getRole(playerUuid);
        return role == null ? -1 : role.color();
    }

    public static int getInstinctHighlight(Entity target) {
        if (!isInstinctEnabled()) return -1;

        // 尸体职业色只开放给“自己已经不在局内存活状态”的观察视角使用，
        // 也就是 spectator / creative 这两种 Wathe 里的“非存活玩家透视”。
        // 这样可以避免存活杀手或其他存活职业，额外看到本不该看到的尸体职业信息。
        if (target instanceof PlayerBodyEntity body) {
            if (isPlayerSpectatingOrCreative()) {
                return getInstinctRoleHighlight(body.getPlayerUuid());
            }
            return -1;
        }

        if (target instanceof ItemEntity || target instanceof NoteEntity || target instanceof FirecrackerEntity)
            return 0xDB9D00;
        if (target instanceof PlayerEntity player) {
            if (GameFunctions.isPlayerSpectatingOrCreative(player)) return -1;
            if (isKiller() && gameComponent.canUseKillerFeatures(player)) return MathHelper.hsvToRgb(0F, 1.0F, 0.6F);
            if (gameComponent.isInnocent(player)) {
                float mood = PlayerMoodComponent.KEY.get(target).getMood();
                if (mood < GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
                    return 0x171DC6;
                } else if (mood < GameConstants.MID_MOOD_THRESHOLD) {
                    return 0x1FAFAF;
                } else {
                    return 0x4EDD35;
                }
            }
            if (isPlayerSpectatingOrCreative()) return 0xFFFFFF;
        }
        return -1;
    }

    public static boolean isInstinctEnabled() {
        return instinctKeybind.isPressed() && ((isKiller() && isPlayerAliveAndInSurvival()) || isPlayerSpectatingOrCreative());
    }

    public static int getLockedRenderDistance(boolean ultraPerfMode) {
        return ultraPerfMode ? 2 : 32;
    }

    /**
     * 切到手雷栏位时，在屏幕下方提示当前投掷模式。
     *
     * <p>这里只在“刚切到手雷”那一刻提示一次：
     * 1. 避免玩家一直拿着手雷时每 tick 刷屏；
     * 2. 只要从别的物品切回手雷，就能再次确认当前模式；
     * 3. 如果玩家连续持有同一格手雷，则不重复打扰。</p>
     */
    private static void maybeShowGrenadeThrowModeHint(@NotNull ClientPlayerEntity player) {
        int currentSlot = player.getInventory().selectedSlot;
        boolean isHoldingGrenade = player.getMainHandStack().isOf(WatheItems.GRENADE);
        if (isHoldingGrenade && lastGrenadeSelectedSlot != currentSlot) {
            showGrenadeThrowModeMessage(player, "tip.grenade.current_throw_mode");
            lastGrenadeSelectedSlot = currentSlot;
        } else if (!isHoldingGrenade) {
            lastGrenadeSelectedSlot = -1;
        }
    }

    public static void showGrenadeThrowModeSwitchMessage(@NotNull PlayerEntity player) {
        showGrenadeThrowModeMessage(player, "tip.grenade.switch_throw_mode");
    }

    public static void showGrenadeThrowModeMessage(@NotNull PlayerEntity player, @NotNull String translationKey) {
        PlayerGrenadeComponent.ThrowMode throwMode = PlayerGrenadeComponent.KEY.get(player).getThrowMode();
        MutableText message = Text.translatable(translationKey, throwMode.getDisplayText()).formatted(Formatting.WHITE);
        player.sendMessage(message, true);
    }
}
