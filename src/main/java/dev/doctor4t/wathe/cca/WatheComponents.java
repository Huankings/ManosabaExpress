package dev.doctor4t.wathe.cca;

import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import org.ladysnake.cca.api.v3.scoreboard.ScoreboardComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.scoreboard.ScoreboardComponentInitializer;
import org.ladysnake.cca.api.v3.world.WorldComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.world.WorldComponentInitializer;

public class WatheComponents implements WorldComponentInitializer, EntityComponentInitializer, ScoreboardComponentInitializer {
    @Override
    public void registerWorldComponentFactories(@NotNull WorldComponentFactoryRegistry registry) {
        registry.register(TrainWorldComponent.KEY, TrainWorldComponent::new);
        registry.register(GameWorldComponent.KEY, GameWorldComponent::new);
        registry.register(MapVariablesWorldComponent.KEY, MapVariablesWorldComponent::new);
        registry.register(MapEnhancementsWorldComponent.KEY, MapEnhancementsWorldComponent::new);
        registry.register(WorldBlackoutComponent.KEY, WorldBlackoutComponent::new);
        registry.register(GameTimeComponent.KEY, GameTimeComponent::new);
        registry.register(AutoStartComponent.KEY, AutoStartComponent::new);
        registry.register(GameRoundEndComponent.KEY, GameRoundEndComponent::new);
        registry.register(TaskPointWorldComponent.KEY, TaskPointWorldComponent::new);
    }

    @Override
    public void registerEntityComponentFactories(@NotNull EntityComponentFactoryRegistry registry) {
        /*
         * 玩法生命状态是一局内的临时授权：
         * 死亡 / 重生后必须清空，避免“特殊旁观仍存活”的标记跨生命残留。
         */
        registry.beginRegistration(PlayerEntity.class, PlayerLifeStateComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerLifeStateComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerMoodComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerMoodComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerStaminaComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerStaminaComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerShopComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerShopComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerPoisonComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerPoisonComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerPsychoComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerPsychoComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerNoteComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerNoteComponent::new);
        /*
         * 停电药水归属是单局临时状态：
         * 它只用于区分“Wathe 停电系统自己刷出的短时夜视/失明”，
         * 死亡、重生或新局初始化都不应该继承。
         */
        registry.beginRegistration(PlayerEntity.class, PlayerBlackoutEffectComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerBlackoutEffectComponent::new);
        /*
         * 手雷投掷模式属于“玩家个人偏好”，不是一局内临时状态。
         *
         * 这里使用 CHARACTER 复制策略，目的是：
         * 1. 玩家死亡 / 重生后依然保留自己上次切换过的投掷方式；
         * 2. 重新拿到手雷时不会又被强制变回默认模式；
         * 3. 和用户需求一致，保证“后续再次使用手雷的时候仍然保持上次模式”。
         */
        registry.beginRegistration(PlayerEntity.class, PlayerGrenadeComponent.KEY).respawnStrategy(RespawnCopyStrategy.CHARACTER).end(PlayerGrenadeComponent::new);
        /*
         * 本能键输入模式同样是玩家个人偏好。
         *
         * 使用 CHARACTER 复制策略可以保证玩家死亡 / 重生后仍保留自己的选择：
         * 喜欢开关模式的玩家不需要每局或每次重生重新设置；
         * 喜欢长按模式的玩家也不会被其他人的选择影响。
         */
        registry.beginRegistration(PlayerEntity.class, PlayerInstinctComponent.KEY).respawnStrategy(RespawnCopyStrategy.CHARACTER).end(PlayerInstinctComponent::new);
    }

    @Override
    public void registerScoreboardComponentFactories(@NotNull ScoreboardComponentFactoryRegistry registry) {
        registry.registerScoreboardComponent(ScoreboardRoleSelectorComponent.KEY, ScoreboardRoleSelectorComponent::new);
        registry.registerScoreboardComponent(MapVotingComponent.KEY, MapVotingComponent::new);
        registry.registerScoreboardComponent(GameRoundEndComponent.KEY, GameRoundEndComponent::new);
    }
}
