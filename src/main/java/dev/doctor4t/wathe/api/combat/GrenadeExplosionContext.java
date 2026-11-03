package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.entity.GrenadeEntity;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheParticles;
import dev.doctor4t.wathe.index.WatheSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端手雷碰撞后的公开爆炸上下文。
 *
 * <p>扩展可以只复用需要的默认步骤。例如假手雷调用声音和粒子但不调用玩家伤害；
 * 无声手雷调用粒子与玩家伤害但不调用声音。实体最终销毁由 Wathe 统一完成，handler 不应自行 discard。</p>
 */
public final class GrenadeExplosionContext {
    public static final double DEFAULT_RADIUS = 3.0D;

    private final ServerWorld world;
    private final GrenadeEntity grenade;
    private final HitResult hitResult;
    private final ItemStack projectileStack;
    private final ItemStack sourceStack;

    public GrenadeExplosionContext(
            @NotNull ServerWorld world,
            @NotNull GrenadeEntity grenade,
            @NotNull HitResult hitResult
    ) {
        this.world = world;
        this.grenade = grenade;
        this.hitResult = hitResult;
        this.projectileStack = grenade.getStack().copyWithCount(1);
        /*
         * Wathe 原版飞行实体显示的是 thrown_grenade 占位物品，但死亡与回放应该显示玩家购买的 grenade。
         * 扩展若把自己的真实物品栈写进实体，则原样保留，供无声/假手雷识别和本地化。
         */
        this.sourceStack = this.projectileStack.isEmpty() || this.projectileStack.isOf(WatheItems.THROWN_GRENADE)
                ? WatheItems.GRENADE.getDefaultStack()
                : this.projectileStack.copy();
    }

    public @NotNull ServerWorld world() {
        return this.world;
    }

    public @NotNull GrenadeEntity grenade() {
        return this.grenade;
    }

    public @NotNull HitResult hitResult() {
        return this.hitResult;
    }

    /** 飞行实体实际携带的渲染物品栈。 */
    public @NotNull ItemStack projectileStack() {
        return this.projectileStack.copy();
    }

    /** 玩家实际投出的玩法物品栈；普通手雷会自动从 thrown_grenade 还原为 grenade。 */
    public @NotNull ItemStack sourceStack() {
        return this.sourceStack.copy();
    }

    public @Nullable Entity owner() {
        return this.grenade.getOwner();
    }

    public @Nullable ServerPlayerEntity playerOwner() {
        return this.grenade.getOwner() instanceof ServerPlayerEntity player ? player : null;
    }

    /** 保持 Wathe 当前轴对齐爆炸范围语义，避免公开化时悄悄改成球形距离。 */
    public @NotNull Box explosionBox(double radius) {
        return this.grenade.getBoundingBox().expand(radius);
    }

    public void playDefaultSound() {
        this.world.playSound(
                null,
                this.grenade.getBlockPos(),
                WatheSounds.ITEM_GRENADE_EXPLODE,
                SoundCategory.PLAYERS,
                5.0F,
                1.0F + this.grenade.getRandom().nextFloat() * 0.1F - 0.05F
        );
    }

    public void spawnDefaultParticles() {
        spawnDefaultParticles(this.projectileStack.isEmpty()
                ? WatheItems.THROWN_GRENADE.getDefaultStack()
                : this.projectileStack);
    }

    public void spawnDefaultParticles(@NotNull ItemStack fragmentStack) {
        this.world.spawnParticles(WatheParticles.BIG_EXPLOSION,
                this.grenade.getX(), this.grenade.getY() + 0.1F, this.grenade.getZ(), 1, 0, 0, 0, 0);
        this.world.spawnParticles(ParticleTypes.SMOKE,
                this.grenade.getX(), this.grenade.getY() + 0.1F, this.grenade.getZ(), 100, 0, 0, 0, 0.2F);
        this.world.spawnParticles(
                new ItemStackParticleEffect(ParticleTypes.ITEM, fragmentStack.copyWithCount(1)),
                this.grenade.getX(),
                this.grenade.getY() + 0.1F,
                this.grenade.getZ(),
                100,
                0,
                0,
                0,
                1.0F
        );
    }

    public void damageDefaultPlayers() {
        damagePlayers(this.sourceStack, DEFAULT_RADIUS);
    }

    /**
     * 使用 Wathe 统一死亡入口伤害范围内玩家，并把给定物品写入死亡/护盾回放。
     * 范围判定故意沿用旧版“实体包围盒扩张后包含玩家坐标”的规则。
     */
    public void damagePlayers(@NotNull ItemStack replayStack, double radius) {
        NbtCompound replayItemData = GameFunctions.createReplayItemData(this.world, replayStack.copyWithCount(1));
        Box explosionBox = explosionBox(radius);
        for (ServerPlayerEntity player : this.world.getPlayers(serverPlayer ->
                explosionBox.contains(serverPlayer.getPos())
                        && GameFunctions.isPlayerAliveAndSurvival(serverPlayer))) {
            GameFunctions.killPlayer(
                    player,
                    true,
                    this.grenade.getOwner() instanceof PlayerEntity playerOwner ? playerOwner : null,
                    GameConstants.DeathReasons.GRENADE,
                    replayItemData
            );
        }
    }

    public void runDefaultExplosion() {
        playDefaultSound();
        spawnDefaultParticles();
        damageDefaultPlayers();
    }
}
