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
        registry.register(WorldBlackoutComponent.KEY, WorldBlackoutComponent::new);
        registry.register(GameTimeComponent.KEY, GameTimeComponent::new);
        registry.register(AutoStartComponent.KEY, AutoStartComponent::new);
        registry.register(GameRoundEndComponent.KEY, GameRoundEndComponent::new);
        registry.register(TaskPointWorldComponent.KEY, TaskPointWorldComponent::new);
    }

    @Override
    public void registerEntityComponentFactories(@NotNull EntityComponentFactoryRegistry registry) {
        registry.beginRegistration(PlayerEntity.class, PlayerMoodComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerMoodComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerShopComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerShopComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerPoisonComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerPoisonComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerPsychoComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerPsychoComponent::new);
        registry.beginRegistration(PlayerEntity.class, PlayerNoteComponent.KEY).respawnStrategy(RespawnCopyStrategy.NEVER_COPY).end(PlayerNoteComponent::new);
        /*
         * 手雷投掷模式属于“玩家个人偏好”，不是一局内临时状态。
         *
         * 这里使用 CHARACTER 复制策略，目的是：
         * 1. 玩家死亡 / 重生后依然保留自己上次切换过的投掷方式；
         * 2. 重新拿到手雷时不会又被强制变回默认模式；
         * 3. 和用户需求一致，保证“后续再次使用手雷的时候仍然保持上次模式”。
         */
        registry.beginRegistration(PlayerEntity.class, PlayerGrenadeComponent.KEY).respawnStrategy(RespawnCopyStrategy.CHARACTER).end(PlayerGrenadeComponent::new);
    }

    @Override
    public void registerScoreboardComponentFactories(@NotNull ScoreboardComponentFactoryRegistry registry) {
        registry.registerScoreboardComponent(ScoreboardRoleSelectorComponent.KEY, ScoreboardRoleSelectorComponent::new);
    }
}
