package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.ShootMuzzleS2CPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端收到一次枪击包后的公开上下文。
 *
 * <p>扩展职业可以用它接管自定义枪械，同时复用 Wathe 的公共枪击外壳：
 * 枪声、枪口同步、命中回放、击杀入口和冷却修正都不需要再复制一遍。</p>
 */
public final class GunShotContext {
    private final ServerPlayerEntity shooter;
    private final ItemStack stack;
    private final int targetEntityId;
    private PlayerEntity cachedPlayerTarget;

    public GunShotContext(@NotNull ServerPlayerEntity shooter, @NotNull ItemStack stack, int targetEntityId) {
        this.shooter = shooter;
        this.stack = stack;
        this.targetEntityId = targetEntityId;
    }

    public @NotNull ServerPlayerEntity shooter() {
        return this.shooter;
    }

    public @NotNull ItemStack stack() {
        return this.stack;
    }

    public int targetEntityId() {
        return this.targetEntityId;
    }

    public @Nullable Entity targetEntity() {
        return this.targetEntityId < 0 ? null : this.shooter.getServerWorld().getEntityById(this.targetEntityId);
    }

    public boolean isCoolingDown() {
        return this.shooter.getItemCooldownManager().isCoolingDown(this.stack.getItem());
    }

    public boolean isCreative() {
        return this.shooter.isCreative();
    }

    /**
     * 读取并校验“存活玩家目标”。
     *
     * <p>扩展枪械如果只允许击中局内存活玩家，应优先使用这个方法。
     * 它会同时检查实体类型、Wathe 玩法存活状态和距离，并缓存命中目标，
     * 让后续 {@link #modifyCooldown(int)} 可以知道这次冷却修正对应谁。</p>
     */
    public @Nullable PlayerEntity alivePlayerTarget(double range) {
        return alivePlayerTarget(range, true);
    }

    public @Nullable PlayerEntity alivePlayerTarget(double range, boolean inclusive) {
        Entity entity = targetEntity();
        if (!(entity instanceof PlayerEntity target)
                || !GameFunctions.isPlayerAliveAndSurvival(target)
                || !TargetVisibilityApi.canAttackPlayer(this.shooter, target)
                || !isWithinRange(target, range, inclusive)) {
            this.cachedPlayerTarget = null;
            return null;
        }
        this.cachedPlayerTarget = target;
        return target;
    }

    /**
     * 读取“玩家目标”，但不检查目标是否仍按玩法存活。
     *
     * <p>Wathe 默认左轮保留这个较宽入口，是为了兼容旧逻辑：服务端只按客户端传来的玩家 id
     * 和距离判定命中，再由 {@code GameFunctions.killPlayer(...)} 自己决定这次死亡是否成立。</p>
     */
    public @Nullable PlayerEntity playerTarget(double range) {
        return playerTarget(range, true);
    }

    public @Nullable PlayerEntity playerTarget(double range, boolean inclusive) {
        Entity entity = targetEntity();
        if (!(entity instanceof PlayerEntity target)
                || !TargetVisibilityApi.canAttackPlayer(this.shooter, target)
                || !isWithinRange(target, range, inclusive)) {
            this.cachedPlayerTarget = null;
            return null;
        }
        this.cachedPlayerTarget = target;
        return target;
    }

    public @Nullable PlayerEntity cachedPlayerTarget() {
        return this.cachedPlayerTarget;
    }

    private boolean isWithinRange(@NotNull PlayerEntity target, double range, boolean inclusive) {
        float distance = target.distanceTo(this.shooter);
        return inclusive ? distance <= range : distance < range;
    }

    /**
     * 播放 Wathe 默认左轮“扣扳机”声音。
     *
     * <p>自定义枪械如果只是后续命中结算不同，可以复用这个方法；
     * 无声枪或命中皮套后取消的场景则不应调用。</p>
     */
    public void playDefaultClickSound() {
        this.shooter.getWorld().playSound(
                null,
                this.shooter.getX(),
                this.shooter.getEyeY(),
                this.shooter.getZ(),
                dev.doctor4t.wathe.index.WatheSounds.ITEM_REVOLVER_CLICK,
                SoundCategory.PLAYERS,
                0.5F,
                1.0F + this.shooter.getRandom().nextFloat() * 0.1F - 0.05F
        );
    }

    /**
     * 播放 Wathe 默认枪响。
     */
    public void playDefaultShootSound() {
        this.shooter.getWorld().playSound(
                null,
                this.shooter.getX(),
                this.shooter.getEyeY(),
                this.shooter.getZ(),
                dev.doctor4t.wathe.index.WatheSounds.ITEM_REVOLVER_SHOOT,
                SoundCategory.PLAYERS,
                5.0F,
                1.0F + this.shooter.getRandom().nextFloat() * 0.1F - 0.05F
        );
    }

    /**
     * 向追踪该玩家的客户端同步枪口火花。
     *
     * <p>服务端接管枪击后如果返回 HANDLED，就需要自己决定是否调用它；
     * Wathe 默认逻辑不会再帮已经接管的扩展补一次枪口包。</p>
     */
    public void sendMuzzle() {
        for (ServerPlayerEntity tracking : PlayerLookup.tracking(this.shooter)) {
            ServerPlayNetworking.send(tracking, new ShootMuzzleS2CPayload(this.shooter.getUuidAsString()));
        }
        ServerPlayNetworking.send(this.shooter, new ShootMuzzleS2CPayload(this.shooter.getUuidAsString()));
    }

    /**
     * 记录“枪械命中玩家”的通用回放事件。
     *
     * <p>这里只记录命中，不代表目标已经死亡。真正死亡仍由 {@link #killTarget(PlayerEntity)}
     * 或扩展自己的死亡入口推进。</p>
     */
    public void recordGunHit(@NotNull PlayerEntity target) {
        if (target instanceof ServerPlayerEntity serverTarget) {
            GameRecordManager.recordItemHit(
                    this.shooter,
                    this.stack,
                    GameConstants.DeathReasons.GUN,
                    serverTarget,
                    null
            );
        }
    }

    /**
     * 通过 Wathe 统一死亡入口击杀目标，并返回这次调用是否最终确认死亡。
     *
     * <p>返回值会避开护盾、免死、特殊存活拦截等场景，适合赏金枪、强盗枪这类
     * “只有真正杀死人之后才结算奖励/掉枪”的扩展逻辑。</p>
     */
    public boolean killTarget(@NotNull PlayerEntity target) {
        boolean targetWasAlive = GameFunctions.isPlayerAliveAndSurvival(target);
        GameFunctions.killPlayer(target, true, this.shooter, GameConstants.DeathReasons.GUN);
        return targetWasAlive && !GameFunctions.isPlayerAliveAndSurvival(target);
    }

    /**
     * 读取当前枪械在 Wathe 默认规则下的基础冷却。
     */
    public int defaultCooldown() {
        Item item = this.stack.getItem();
        return this.stack.isOf(WatheItems.REVOLVER)
                ? GameConstants.getRevolverCooldown(this.shooter)
                : GameConstants.ITEM_COOLDOWNS.getOrDefault(item, 0);
    }

    /**
     * 把基础冷却交给所有已注册冷却修正器处理。
     */
    public int modifyCooldown(int baseCooldown) {
        return GunShotApi.modifyCooldown(new GunCooldownContext(
                this.shooter,
                this.stack,
                this.cachedPlayerTarget,
                baseCooldown
        ));
    }

    /**
     * 直接把修正后的冷却写回当前开火物品。
     */
    public void applyCooldown(int baseCooldown) {
        this.shooter.getItemCooldownManager().set(this.stack.getItem(), modifyCooldown(baseCooldown));
    }
}
