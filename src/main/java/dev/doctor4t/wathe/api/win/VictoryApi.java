package dev.doctor4t.wathe.api.win;

import dev.doctor4t.wathe.cca.GameRoundEndComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wathe 胜利机制的公开接入点。
 *
 * <p>原本扩展职业需要 mixin 到 {@code MurderGameMode#tickServerGameLoop}、
 * {@code GameRoundEndComponent#didWin} 和客户端 {@code RoundTextRenderer}。
 * 现在扩展只要注册一个规则，就可以完成三类需求：</p>
 *
 * <p>1. 独立胜利：写入独立公告，并把赢家放到结算页右侧“独立胜利阵营”；</p>
 * <p>2. 保活：某个职业/词条还活着时，阻止普通杀手/乘客胜利提前结束；</p>
 * <p>3. 共胜：普通阵营获胜时，额外把某些职业/词条玩家标记为真正赢家。</p>
 */
public final class VictoryApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<PrioritizedRule> RULE_COMPARATOR =
            Comparator.<PrioritizedRule>comparingInt(PrioritizedRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedRule::order).reversed());
    private static final List<PrioritizedRule> RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private VictoryApi() {
    }

    /**
     * 注册一个胜利规则。
     *
     * <p>高 priority 的规则先执行；同 priority 下，后注册的规则先执行。
     * 这可以满足“恋人优先于双重人格”这类需求：恋人注册时给更高 priority 即可。</p>
     */
    public static synchronized void registerRule(
            @NotNull Identifier id,
            int priority,
            @NotNull VictoryRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        RULES.removeIf(entry -> entry.id().equals(id));
        RULES.add(new PrioritizedRule(id, priority, nextOrder++, rule));
        RULES.sort(RULE_COMPARATOR);
    }

    /**
     * Wathe 本体在每次 MurderGameMode tick 计算出原版胜利状态后调用。
     *
     * <p>扩展模组通常不需要直接调用这个方法；它是本体和自定义 GameMode
     * 对接统一胜利规则时使用的分发入口。</p>
     */
    public static @NotNull VictoryResult evaluate(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            GameFunctions.@NotNull WinStatus vanillaWinStatus
    ) {
        List<ServerPlayerEntity> alivePlayers = world.getPlayers(GameFunctions::isPlayerAliveAndSurvival);
        VictoryContext context = new VictoryContext(world, gameWorld, List.copyOf(alivePlayers), vanillaWinStatus);

        for (PrioritizedRule entry : ruleSnapshot()) {
            VictoryResult result = entry.rule().evaluate(context);
            if (result != null && result.action() != VictoryAction.PASS) {
                return result;
            }
        }
        return VictoryResult.pass();
    }

    public static void endGameWithCustomVictory(
            @NotNull ServerWorld world,
            @NotNull CustomVictory victory
    ) {
        /*
         * 自定义胜利仍写一个原版 WinStatus，主要是为了兼容旧的结算生命周期。
         * 真正的胜负、顶部文案和左右分组都会优先读取 CustomVictory 数据。
         */
        GameRoundEndComponent.KEY.get(world)
                .setRoundEndData(world.getPlayers(), GameFunctions.WinStatus.KILLERS, victory, List.of());
        GameFunctions.stopGame(world);
    }

    public static void endGameWithVanillaWin(
            @NotNull ServerWorld world,
            GameFunctions.@NotNull WinStatus winStatus,
            @NotNull Collection<UUID> extraWinnerUuids
    ) {
        GameRoundEndComponent.KEY.get(world)
                .setRoundEndData(world.getPlayers(), winStatus, null, extraWinnerUuids);
        GameFunctions.stopGame(world);
    }

    public static @NotNull List<UUID> uuidsFromPlayers(@NotNull Collection<? extends PlayerEntity> players) {
        return CustomVictoryGroup.uuidsFromPlayers(players);
    }

    private static synchronized List<PrioritizedRule> ruleSnapshot() {
        return List.copyOf(RULES);
    }

    @FunctionalInterface
    public interface VictoryRule {
        @NotNull VictoryResult evaluate(@NotNull VictoryContext context);
    }

    public record VictoryContext(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull List<ServerPlayerEntity> alivePlayers,
            GameFunctions.@NotNull WinStatus vanillaWinStatus
    ) {
        public boolean hasVanillaWinner() {
            return this.vanillaWinStatus != GameFunctions.WinStatus.NONE;
        }
    }

    public enum VictoryAction {
        PASS,
        KEEP_RUNNING,
        VANILLA_WIN,
        CUSTOM_WIN
    }

    public record VictoryResult(
            @NotNull VictoryAction action,
            @Nullable GameFunctions.WinStatus winStatus,
            @Nullable CustomVictory customVictory,
            @NotNull List<UUID> extraWinnerUuids
    ) {
        public VictoryResult {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(extraWinnerUuids, "extraWinnerUuids");
            extraWinnerUuids = List.copyOf(extraWinnerUuids);
        }

        public static @NotNull VictoryResult pass() {
            return new VictoryResult(VictoryAction.PASS, null, null, List.of());
        }

        public static @NotNull VictoryResult keepRunning() {
            return new VictoryResult(VictoryAction.KEEP_RUNNING, null, null, List.of());
        }

        public static @NotNull VictoryResult vanillaWin(
                GameFunctions.@NotNull WinStatus winStatus,
                @NotNull Collection<UUID> extraWinnerUuids
        ) {
            return new VictoryResult(VictoryAction.VANILLA_WIN, winStatus, null, List.copyOf(extraWinnerUuids));
        }

        public static @NotNull VictoryResult customWin(@NotNull CustomVictory victory) {
            return new VictoryResult(VictoryAction.CUSTOM_WIN, null, victory, List.of());
        }
    }

    private record PrioritizedRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull VictoryRule rule
    ) {
    }
}
