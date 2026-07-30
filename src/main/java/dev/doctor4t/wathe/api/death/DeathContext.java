package dev.doctor4t.wathe.api.death;

import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一次 Wathe 统一死亡流程的上下文。
 *
 * <p>该对象由 Wathe 在 {@code GameFunctions.killPlayer(...)} 开头创建，
 * 随着流程推进更新状态。扩展 handler 应读取这里的阶段状态，
 * 不要再用 ThreadLocal 自己猜“这次到底有没有真正死亡”。</p>
 */
public final class DeathContext {
    private final PlayerEntity victim;
    private final boolean requestedSpawnBody;
    @Nullable
    private final PlayerEntity killer;
    private final Identifier deathReason;
    private final boolean victimAliveAtStart;
    @Nullable
    private final NbtCompound extraDeathDataAtStart;

    private boolean cancelledBeforeAttempt;
    private boolean beforeAttemptStarted;
    private boolean cancelledByProtection;
    private boolean blockedByShield;
    private boolean fatalIntercepted;
    private boolean markedDead;
    private boolean deathRecorded;
    private boolean bodySpawned;
    private boolean completedDefaultFlow;

    public DeathContext(@NotNull PlayerEntity victim,
                        boolean requestedSpawnBody,
                        @Nullable PlayerEntity killer,
                        @NotNull Identifier deathReason,
                        @Nullable NbtCompound extraDeathDataAtStart) {
        this.victim = victim;
        this.requestedSpawnBody = requestedSpawnBody;
        this.killer = killer;
        this.deathReason = deathReason;
        this.victimAliveAtStart = GameFunctions.isPlayerAliveAndSurvival(victim);
        this.extraDeathDataAtStart = extraDeathDataAtStart == null ? null : extraDeathDataAtStart.copy();
    }

    public @NotNull PlayerEntity victim() {
        return this.victim;
    }

    public boolean requestedSpawnBody() {
        return this.requestedSpawnBody;
    }

    public @Nullable PlayerEntity killer() {
        return this.killer;
    }

    public @NotNull Identifier deathReason() {
        return this.deathReason;
    }

    public boolean victimAliveAtStart() {
        return this.victimAliveAtStart;
    }

    public boolean victimAliveNow() {
        return GameFunctions.isPlayerAliveAndSurvival(this.victim);
    }

    public @Nullable NbtCompound extraDeathDataAtStart() {
        return this.extraDeathDataAtStart == null ? null : this.extraDeathDataAtStart.copy();
    }

    public @Nullable ServerPlayerEntity serverVictim() {
        return this.victim instanceof ServerPlayerEntity serverVictim ? serverVictim : null;
    }

    public @Nullable ServerPlayerEntity serverKiller() {
        return this.killer instanceof ServerPlayerEntity serverKiller ? serverKiller : null;
    }

    public boolean cancelledBeforeAttempt() {
        return this.cancelledBeforeAttempt;
    }

    public boolean beforeAttemptStarted() {
        return this.beforeAttemptStarted;
    }

    public boolean cancelledByProtection() {
        return this.cancelledByProtection;
    }

    public boolean blockedByShield() {
        return this.blockedByShield;
    }

    public boolean fatalIntercepted() {
        return this.fatalIntercepted;
    }

    public boolean markedDead() {
        return this.markedDead;
    }

    public boolean deathRecorded() {
        return this.deathRecorded;
    }

    public boolean bodySpawned() {
        return this.bodySpawned;
    }

    public boolean completedDefaultFlow() {
        return this.completedDefaultFlow;
    }

    /**
     * 本次调用是否已经从“玩法存活”推进到“玩法死亡”。
     *
     * <p>赏金奖励、时间狭缝、死亡后清理这类机制应优先看这个状态，
     * 避免护盾、免死、致死转化或重复死亡也触发“击杀成功”。</p>
     */
    public boolean confirmedDeath() {
        return this.victimAliveAtStart && this.completedDefaultFlow && !victimAliveNow();
    }

    public void markCancelledBeforeAttempt() {
        this.cancelledBeforeAttempt = true;
    }

    public void markBeforeAttemptStarted() {
        this.beforeAttemptStarted = true;
    }

    public void markCancelledByProtection() {
        this.cancelledByProtection = true;
    }

    public void markBlockedByShield() {
        this.blockedByShield = true;
    }

    public void markFatalIntercepted() {
        this.fatalIntercepted = true;
    }

    public void markDead() {
        this.markedDead = true;
    }

    public void markDeathRecorded() {
        this.deathRecorded = true;
    }

    public void markBodySpawned() {
        this.bodySpawned = true;
    }

    public void markCompletedDefaultFlow() {
        this.completedDefaultFlow = true;
    }
}
