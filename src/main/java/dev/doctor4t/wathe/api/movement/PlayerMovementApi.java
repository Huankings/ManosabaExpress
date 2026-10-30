package dev.doctor4t.wathe.api.movement;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.stamina.PlayerStaminaApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerStaminaComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 玩家移动速度公开 API。
 *
 * <p>Wathe 本体负责把局内存活玩家的基础走路 / 疾跑速度固定到玩法数值；
 * 扩展职业如果需要加速、减速或临时覆盖速度，应注册 {@link #registerSpeedModifier(Identifier, int, MovementSpeedModifier)}。
 * 多个扩展可以返回加法、倍率或覆盖结果，Wathe 会按 priority 从高到低顺序累计应用，
 * 避免多个 mod 同时 mixin {@code PlayerEntity#getMovementSpeed()} 时互相覆盖。</p>
 */
public final class PlayerMovementApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());

    private static final List<Entry> SPEED_MODIFIERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private PlayerMovementApi() {
    }

    /**
     * 注册一条移动速度修正规则。
     *
     * <p>priority 越大越先执行；同 priority 下后注册的规则先执行。
     * 同一个 id 再次注册会替换旧规则，方便扩展在配置重载或兼容层初始化时安全重复调用。</p>
     */
    public static void registerSpeedModifier(@NotNull Identifier id, int priority, @NotNull MovementSpeedModifier modifier) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        synchronized (SPEED_MODIFIERS) {
            SPEED_MODIFIERS.removeIf(entry -> entry.id().equals(id));
            SPEED_MODIFIERS.add(new Entry(id, priority, nextOrder++, modifier));
            SPEED_MODIFIERS.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 解析最终移动速度。
     *
     * @param vanillaSpeed 原版返回值，包含速度 / 缓慢等状态效果，可供扩展按需参考。
     * @param baseSpeed    Wathe 已经换算好的局内基础速度，也是所有叠加从这里开始的当前值。
     */
    public static float resolveMovementSpeed(@NotNull PlayerEntity player, float vanillaSpeed, float baseSpeed) {
        float currentSpeed = Math.max(0.0F, baseSpeed);
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        Role role = gameWorld.getRole(player);
        PlayerStaminaComponent stamina = PlayerStaminaComponent.KEY.get(player);
        PlayerStaminaApi.MoodPenaltyProfile moodPenaltyProfile = PlayerStaminaApi.resolveMoodPenaltyProfile(player);

        for (Entry entry : snapshot()) {
            MovementSpeedContext context = new MovementSpeedContext(
                    player,
                    gameWorld,
                    role,
                    stamina,
                    moodPenaltyProfile,
                    player.isSprinting(),
                    vanillaSpeed,
                    baseSpeed,
                    currentSpeed
            );
            MovementSpeedResult result = entry.modifier().modify(context);
            currentSpeed = applyResult(currentSpeed, result);
        }

        return Math.max(0.0F, currentSpeed);
    }

    /**
     * 判断 Wathe 是否正在接管该玩家的自主移动速度。
     *
     * <p>速度覆写、体力移动限制和疾跑起跳额外速度必须使用完全相同的生效范围：
     * 只处理正在进行的 Wathe 对局中的存活生存/冒险玩家。大厅、停局、创造、旁观玩家继续使用原版行为。
     * 把判断集中在公开 API 中，可以避免本体和扩展各自复制条件后逐渐产生客户端/服务端差异。</p>
     */
    public static boolean isMovementControlActive(@NotNull PlayerEntity player) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        return gameWorld != null && gameWorld.isRunning() && GameFunctions.isPlayerAliveAndSurvival(player);
    }

    /**
     * 解析疾跑起跳时应叠加的额外水平速度。
     *
     * @param player       正在跳跃的玩家
     * @param vanillaBoost 原版准备追加的水平速度；Minecraft 1.21.1 默认是固定 {@code 0.2}
     * @return 非 Wathe 控制范围保留原版值；局内存活玩家使用世界级
     * {@link GameWorldComponent#getExtraSprintJumpSpeed()} 配置
     *
     * <p>原版固定冲量本身不读取移动速度属性，因此速度/缓慢药水不会乘到这段 {@code 0.2} 上。
     * 本 API 保留相同语义：指令数值就是最终额外水平速度，不再人为叠加药水倍率。</p>
     */
    public static float resolveExtraSprintJumpSpeed(@NotNull PlayerEntity player, float vanillaBoost) {
        if (!isMovementControlActive(player)) {
            return Math.max(0.0F, vanillaBoost);
        }
        return Math.max(0.0F, GameWorldComponent.KEY.get(player.getWorld()).getExtraSprintJumpSpeed());
    }

    public static boolean canSelfMove(@NotNull PlayerEntity player) {
        return PlayerStaminaApi.canSelfMove(player);
    }

    public static boolean canJump(@NotNull PlayerEntity player) {
        return PlayerStaminaApi.canJump(player);
    }

    private static float applyResult(float currentSpeed, @Nullable MovementSpeedResult result) {
        if (result == null || result.operation() == MovementSpeedResult.Operation.PASS || !Float.isFinite(result.value())) {
            return currentSpeed;
        }
        return switch (result.operation()) {
            case PASS -> currentSpeed;
            case ADD -> currentSpeed + result.value();
            case MULTIPLY -> currentSpeed * result.value();
            case OVERRIDE -> result.value();
        };
    }

    private static List<Entry> snapshot() {
        synchronized (SPEED_MODIFIERS) {
            return List.copyOf(SPEED_MODIFIERS);
        }
    }

    @FunctionalInterface
    public interface MovementSpeedModifier {
        @NotNull MovementSpeedResult modify(@NotNull MovementSpeedContext context);
    }

    public record MovementSpeedContext(
            @NotNull PlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @NotNull PlayerStaminaComponent stamina,
            @NotNull PlayerStaminaApi.MoodPenaltyProfile moodPenaltyProfile,
            boolean sprinting,
            float vanillaSpeed,
            float baseSpeed,
            float currentSpeed
    ) {
    }

    public record MovementSpeedResult(@NotNull Operation operation, float value) {
        private static final MovementSpeedResult PASS = new MovementSpeedResult(Operation.PASS, 0.0F);

        public static @NotNull MovementSpeedResult pass() {
            return PASS;
        }

        public static @NotNull MovementSpeedResult add(float amount) {
            return new MovementSpeedResult(Operation.ADD, amount);
        }

        public static @NotNull MovementSpeedResult multiply(float factor) {
            return new MovementSpeedResult(Operation.MULTIPLY, factor);
        }

        public static @NotNull MovementSpeedResult override(float speed) {
            return new MovementSpeedResult(Operation.OVERRIDE, speed);
        }

        public enum Operation {
            PASS,
            ADD,
            MULTIPLY,
            OVERRIDE
        }
    }

    private record Entry(@NotNull Identifier id, int priority, long order, @NotNull MovementSpeedModifier modifier) {
    }
}
