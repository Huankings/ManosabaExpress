package dev.doctor4t.wathe.api.round;

import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.win.CustomVictory;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 回合结束时“玩家应显示在哪个阵营栏目”的公开 API。
 *
 * <p>这个 API 在服务端生成 {@code GameRoundEndComponent.RoundEndData} 快照时执行，最终结果随后同步给客户端
 * 和全局 scoreboard 结算缓存。因此扩展只需要注册一次服务端规则，不需要 mixin {@code RoundTextRenderer}，
 * 也不需要额外发送客户端网络包。</p>
 *
 * <p>迁移只改变本次结算快照与普通阵营胜负归属，不会改写玩家的真实 {@link Role}、实时 {@link Faction}、
 * 杀手能力、商店、同伙关系或局内胜利人数判定。需要改变那些机制时，应分别接入对应公开 API。</p>
 */
public final class RoundEndFactionApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<RuleEntry> RULE_COMPARATOR =
            Comparator.comparingInt(RuleEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(RuleEntry::order).reversed());
    private static final List<RuleEntry> RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private RoundEndFactionApi() {
    }

    /**
     * 注册结算阵营迁移规则。
     *
     * <p>priority 越大越先执行；同 priority 下后注册者先执行。返回 PASS 会继续询问后续规则，
     * 第一条返回 MOVE_TO 的规则成为该玩家本次结算的最终阵营。同一个 id 重复注册会替换旧规则。</p>
     */
    public static void registerRule(
            @NotNull Identifier id,
            int priority,
            @NotNull RoundEndFactionRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (RULES) {
            RULES.removeIf(entry -> entry.id().equals(id));
            RULES.add(new RuleEntry(id, priority, nextOrder++, rule));
            RULES.sort(RULE_COMPARATOR);
        }
    }

    /**
     * 解析玩家写入结算快照时的最终阵营。
     *
     * <p>普通 PASS 链路最终回退到职业注册阵营。独立胜利的左右分组仍由 {@link CustomVictory} 的赢家 UUID
     * 决定，但这里解析出的阵营仍会保存在普通 RoundEndData 中，保证快照结构一致。</p>
     */
    public static @NotNull Faction resolveFaction(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player,
            @NotNull Role role,
            @NotNull GameFunctions.WinStatus winStatus,
            @Nullable CustomVictory customVictory
    ) {
        Faction baseFaction = role.getFaction();
        RoundEndFactionContext context = new RoundEndFactionContext(
                world,
                gameWorld,
                player,
                role,
                baseFaction,
                winStatus,
                customVictory
        );
        for (RuleEntry entry : ruleSnapshot()) {
            RoundEndFactionResult result = entry.rule().resolve(context);
            if (result != null && result.action() == RoundEndFactionResult.Action.MOVE_TO && result.faction() != null) {
                return result.faction();
            }
        }
        return baseFaction;
    }

    private static @NotNull List<RuleEntry> ruleSnapshot() {
        synchronized (RULES) {
            return List.copyOf(RULES);
        }
    }

    @FunctionalInterface
    public interface RoundEndFactionRule {
        @Nullable RoundEndFactionResult resolve(@NotNull RoundEndFactionContext context);
    }

    public record RoundEndFactionContext(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player,
            @NotNull Role role,
            @NotNull Faction baseFaction,
            @NotNull GameFunctions.WinStatus winStatus,
            @Nullable CustomVictory customVictory
    ) {
    }

    public record RoundEndFactionResult(@NotNull Action action, @Nullable Faction faction) {
        private static final RoundEndFactionResult PASS = new RoundEndFactionResult(Action.PASS, null);

        public static @NotNull RoundEndFactionResult pass() {
            return PASS;
        }

        public static @NotNull RoundEndFactionResult moveTo(@NotNull Faction faction) {
            return new RoundEndFactionResult(Action.MOVE_TO, Objects.requireNonNull(faction, "faction"));
        }

        public enum Action {
            PASS,
            MOVE_TO
        }
    }

    private record RuleEntry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull RoundEndFactionRule rule
    ) {
    }
}
