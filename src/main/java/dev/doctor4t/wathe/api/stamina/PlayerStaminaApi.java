package dev.doctor4t.wathe.api.stamina;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerStaminaComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家体力公开 API。
 *
 * <p>扩展职业需要清空体力、回满体力、增减当前体力或临时调整体力上限时，应统一走这里。
 * 不要再读取玩家 NBT 里的旧 {@code sprintingTicks}，也不要 mixin Wathe 的玩家移动逻辑。</p>
 */
public final class PlayerStaminaApi {
    private PlayerStaminaApi() {
    }

    public static @NotNull PlayerStaminaComponent getComponent(@NotNull PlayerEntity player) {
        return PlayerStaminaComponent.KEY.get(player);
    }

    public static float getStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getStamina();
    }

    public static void setStamina(@NotNull PlayerEntity player, float stamina) {
        getComponent(player).setStamina(stamina);
    }

    public static void addStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addStamina(amount);
    }

    public static void drainStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).drainStamina(amount);
    }

    public static void clearStamina(@NotNull PlayerEntity player) {
        getComponent(player).clearStamina();
    }

    public static void fillStamina(@NotNull PlayerEntity player) {
        getComponent(player).fillStamina();
    }

    public static float getBaseMaxStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getBaseMaxStamina();
    }

    public static float getMaxStamina(@NotNull PlayerEntity player) {
        return getComponent(player).getMaxStamina();
    }

    public static boolean hasFiniteStaminaLimit(@NotNull PlayerEntity player) {
        return getComponent(player).hasFiniteStaminaLimit();
    }

    public static float getMaxStaminaBonus(@NotNull PlayerEntity player) {
        return getComponent(player).getMaxStaminaBonus();
    }

    public static void setMaxStaminaBonus(@NotNull PlayerEntity player, float bonus) {
        getComponent(player).setMaxStaminaBonus(bonus);
    }

    public static void addMaxStaminaBonus(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(amount);
    }

    public static void increaseMaxStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(Math.max(0.0F, amount));
    }

    public static void decreaseMaxStamina(@NotNull PlayerEntity player, float amount) {
        getComponent(player).addMaxStaminaBonus(-Math.max(0.0F, amount));
    }

    public static void resetMaxStaminaBonus(@NotNull PlayerEntity player) {
        getComponent(player).resetMaxStaminaBonus();
    }

    public static boolean isExhausted(@NotNull PlayerEntity player) {
        return isStaminaMechanicsActive(player) && getComponent(player).isExhausted();
    }

    /**
     * 判断当前玩家是否处于 Wathe 的局内体力管控范围。
     *
     * <p>只有“对局运行中 + 玩法存活 + 已经分配职业”的玩家才会被体力限制移动。
     * 这样大厅、旁观、创造、未入局玩家和刚进入服务器但尚未分配身份的玩家不会被 0 体力误锁。</p>
     */
    public static boolean isStaminaMechanicsActive(@NotNull PlayerEntity player) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        return gameWorld.isRunning()
                && GameFunctions.isPlayerAliveAndSurvival(player)
                && gameWorld.getRole(player) != null;
    }

    /**
     * 按当前世界开关与心情值解析体力惩罚档位。
     *
     * <p>规则顺序与用户需求一致：
     * 1. 两个开关默认关闭，此时所有心情都走 HIGH；
     * 2. 只开启中等惩罚时，中等到低落心情全部走 MID；
     * 3. 开启低落惩罚时，心情到达并低于 {@link GameConstants#DEPRESSIVE_MOOD_THRESHOLD} 走 DEPRESSIVE；
     * 4. 中等阈值使用“低于”判断，低落阈值使用“到达并低于”判断。</p>
     */
    public static @NotNull MoodPenaltyProfile resolveMoodPenaltyProfile(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return MoodPenaltyProfile.HIGH;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        float mood = PlayerMoodComponent.KEY.get(player).getMood();
        if (mood <= GameConstants.DEPRESSIVE_MOOD_THRESHOLD) {
            if (gameWorld.isDepressiveMoodStaminaPenaltyEnabled()) {
                return MoodPenaltyProfile.DEPRESSIVE;
            }
            return gameWorld.isMidMoodStaminaPenaltyEnabled() ? MoodPenaltyProfile.MID : MoodPenaltyProfile.HIGH;
        }
        if (mood < GameConstants.MID_MOOD_THRESHOLD && gameWorld.isMidMoodStaminaPenaltyEnabled()) {
            return MoodPenaltyProfile.MID;
        }
        return MoodPenaltyProfile.HIGH;
    }

    public static float getSprintDrainPerTick(@NotNull PlayerEntity player) {
        return switch (resolveMoodPenaltyProfile(player)) {
            case HIGH -> GameConstants.STAMINA_SPRINT_DRAIN_HIGH_MOOD;
            case MID -> GameConstants.STAMINA_SPRINT_DRAIN_MID_MOOD;
            case DEPRESSIVE -> GameConstants.STAMINA_SPRINT_DRAIN_DEPRESSIVE_MOOD;
        };
    }

    public static float getWalkDrainPerTick(@NotNull PlayerEntity player) {
        return resolveMoodPenaltyProfile(player) == MoodPenaltyProfile.DEPRESSIVE
                ? GameConstants.STAMINA_WALK_DRAIN_DEPRESSIVE_MOOD
                : 0.0F;
    }

    public static float getRecoveryPerTick(@NotNull PlayerEntity player) {
        return switch (resolveMoodPenaltyProfile(player)) {
            case HIGH -> GameConstants.STAMINA_RECOVERY_HIGH_MOOD;
            case MID -> GameConstants.STAMINA_RECOVERY_MID_MOOD;
            case DEPRESSIVE -> GameConstants.STAMINA_RECOVERY_DEPRESSIVE_MOOD;
        };
    }

    /**
     * 判断玩家是否仍允许自主疾跑。
     *
     * <p>低落惩罚开启并生效时会直接禁止疾跑；其它档位只在有限体力归零时禁止疾跑。</p>
     */
    public static boolean canSprint(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return true;
        }
        if (resolveMoodPenaltyProfile(player) == MoodPenaltyProfile.DEPRESSIVE) {
            return false;
        }
        PlayerStaminaComponent stamina = getComponent(player);
        return !stamina.hasFiniteStaminaLimit() || stamina.getStamina() > 0.0F;
    }

    /**
     * 判断玩家是否允许水平自主移动。
     *
     * <p>体力归零只会拦截玩家自己的水平移动输入；击退、传送、外部推力等外力位移不在这里处理。</p>
     */
    public static boolean canSelfMove(@NotNull PlayerEntity player) {
        if (!isStaminaMechanicsActive(player)) {
            return true;
        }
        PlayerStaminaComponent stamina = getComponent(player);
        return !stamina.hasFiniteStaminaLimit() || stamina.getStamina() > 0.0F;
    }

    public static boolean canJump(@NotNull PlayerEntity player) {
        return canSelfMove(player);
    }

    public enum MoodPenaltyProfile {
        HIGH,
        MID,
        DEPRESSIVE
    }
}
