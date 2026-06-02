package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.cca.PlayerGrenadeComponent;
import dev.doctor4t.wathe.entity.GrenadeEntity;
import dev.doctor4t.wathe.index.WatheEntities;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

public class GrenadeItem extends Item {
    /**
     * 手雷最多只能蓄力 1 秒。
     * Minecraft 的时间单位是 tick，20 tick = 1 秒。
     */
    private static final int MAX_CHARGE_TIME = 20;

    /**
     * 不蓄力时沿用 wathe 原版的投掷初速度，确保轻点右键时的手感不变。
     */
    private static final float BASE_THROW_SPEED = 0.5F;

    /**
     * 满蓄力时的最大投掷初速度。
     * 这里比原版更高，用来表现“蓄力后扔得更远”的效果。
     */
    private static final float MAX_THROW_SPEED = 1.3F;

    public GrenadeItem(Item.Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(@NotNull World world, @NotNull PlayerEntity user, Hand hand) {
        ItemStack itemStack = user.getStackInHand(hand);

        /*
         * 手雷现在支持两种可切换模式：
         * 1. 蓄力模式：保持当前版本的“按住右键蓄力，松手投掷”；
         * 2. 直投模式：右键立即按原版初速度直接扔出，不再等待松手。
         *
         * 默认模式由 PlayerGrenadeComponent 持久保存，所以这里只读取玩家当前设置。
         */
        if (PlayerGrenadeComponent.KEY.get(user).isDirectThrowMode()) {
            if (!world.isClient) {
                this.throwGrenade(world, user, itemStack, BASE_THROW_SPEED);
            }
            return TypedActionResult.consume(itemStack);
        }

        /*
         * 改为“按住右键开始蓄力”的交互。
         * 真正的投掷动作会在玩家松开右键时，于 onStoppedUsing 中执行。
         */
        user.setCurrentHand(hand);
        return TypedActionResult.consume(itemStack);
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (user.isSpectator() || !(user instanceof PlayerEntity player) || world.isClient) {
            return;
        }

        // 根据本次实际蓄力时长计算投掷速度；不蓄力时仍然是原版 0.5F。
        float throwSpeed = this.getThrowSpeed(stack, user, remainingUseTicks);
        this.throwGrenade(world, player, stack, throwSpeed);
    }

    private void throwGrenade(@NotNull World world, @NotNull PlayerEntity player, @NotNull ItemStack stack, float throwSpeed) {
        world.playSound(null, player.getX(), player.getY(), player.getZ(), WatheSounds.ITEM_GRENADE_THROW, SoundCategory.NEUTRAL, 0.5F, 1F + (world.random.nextFloat() - .5f) / 10f);

        GrenadeEntity grenade = new GrenadeEntity(WatheEntities.GRENADE, world);
        grenade.setOwner(player);
        grenade.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        grenade.setVelocity(player, player.getPitch(), player.getYaw(), 0.0F, throwSpeed, 1.0F);
        world.spawnEntity(grenade);

        /*
         * 手雷的“使用事件”定义为投掷出去那一刻。
         * 实际炸死谁，则交给 GrenadeEntity 爆炸后的死亡记录去补完整。
         */
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            GameRecordManager.recordItemUse(serverPlayer, Registries.ITEM.getId(this), null, null);
        }

        if (!player.isCreative()) {
//            player.getItemCooldownManager().set(this, GameConstants.ITEM_COOLDOWNS.get(this));
        }

        player.incrementStat(Stats.USED.getOrCreateStat(this));
        stack.decrementUnlessCreative(1, player);
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        /*
         * 使用一个很长的持续时间，让玩家可以自由按住右键；
         * 真正能影响手雷速度的只有前 20 tick，也就是 1 秒。
         */
        return 72000;
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        /*
         * 使用弓的动作，这样客户端会显示明显的“蓄力中”姿势，
         * 玩家更容易感知到手雷现在支持按住右键蓄力。
         */
        return UseAction.BOW;
    }

    /**
     * 计算实际参与投掷速度换算的蓄力时间。
     * 超过 1 秒的部分会被截断，避免一直按住时速度无限增长。
     */
    private int getChargeTicks(ItemStack stack, LivingEntity user, int remainingUseTicks) {
        int usedTicks = this.getMaxUseTime(stack, user) - remainingUseTicks;
        return MathHelper.clamp(usedTicks, 0, MAX_CHARGE_TIME);
    }

    /**
     * 将蓄力进度线性映射到投掷速度。
     * 0 秒蓄力 = 原版速度，1 秒满蓄力 = 更远的投掷速度。
     */
    private float getThrowSpeed(ItemStack stack, LivingEntity user, int remainingUseTicks) {
        float chargeProgress = (float) this.getChargeTicks(stack, user, remainingUseTicks) / MAX_CHARGE_TIME;
        return BASE_THROW_SPEED + (MAX_THROW_SPEED - BASE_THROW_SPEED) * chargeProgress;
    }
}
