package dev.doctor4t.wathe.api.economy;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.game.GameConstants;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wathe 金币经济系统的公开接入点。
 *
 * <p>这个 API 同时负责三件事情：</p>
 * <p>1. 声明哪些职业应该显示右上角金币 HUD；</p>
 * <p>2. 声明哪些职业应该吃到 Wathe 的通用被动收入结算；</p>
 * <p>3. 允许扩展在通用被动收入结算前修改本次收入，比如“富豪”双倍收入。</p>
 *
 * <p>原本扩展模组需要 mixin {@code StoreRenderer} 或
 * {@code MurderGameMode#tickServerGameLoop}。现在扩展只需要在初始化阶段注册自己的职业能力，
 * Wathe 本体会在统一位置读取这些注册结果。</p>
 */
public final class EconomyApi {
    public static final int DEFAULT_PRIORITY = 0;
    public static final Identifier MONEY = Wathe.id("money");
    public static final Identifier TASK_MONEY = Wathe.id("task_money");
    public static final String MONEY_ICON = "\uE781";
    public static final String TASK_MONEY_ICON = "\uE782";

    private static final Comparator<PrioritizedBalanceHudPredicate> BALANCE_HUD_COMPARATOR =
            Comparator.<PrioritizedBalanceHudPredicate>comparingInt(PrioritizedBalanceHudPredicate::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedBalanceHudPredicate::order).reversed());
    private static final Comparator<PrioritizedPassiveIncomeRule> PASSIVE_RULE_COMPARATOR =
            Comparator.<PrioritizedPassiveIncomeRule>comparingInt(PrioritizedPassiveIncomeRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedPassiveIncomeRule::order).reversed());
    private static final Comparator<PrioritizedPassiveIncomeModifier> PASSIVE_MODIFIER_COMPARATOR =
            Comparator.<PrioritizedPassiveIncomeModifier>comparingInt(PrioritizedPassiveIncomeModifier::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedPassiveIncomeModifier::order).reversed());

    private static final Set<Role> BALANCE_HUD_ROLES = new HashSet<>();
    private static final Set<Role> PASSIVE_INCOME_ROLES = new HashSet<>();
    private static final Map<Identifier, CurrencyDefinition> CURRENCIES = new LinkedHashMap<>();
    private static final List<PrioritizedBalanceHudPredicate> BALANCE_HUD_PREDICATES = new ArrayList<>();
    private static final List<PrioritizedPassiveIncomeRule> PASSIVE_INCOME_RULES = new ArrayList<>();
    private static final List<PrioritizedPassiveIncomeModifier> PASSIVE_INCOME_MODIFIERS = new ArrayList<>();
    private static long nextOrder = 0L;
    private static long nextCurrencyOrder = 0L;

    static {
        /*
         * 金币保留旧 EconomyApi 的显示规则：杀手能力角色默认可见，注册了金币 HUD 的扩展职业也可见。
         * 具体渲染时仍会额外要求数量 > 0，所以开局前或余额为 0 时不会显示空图标。
         */
        registerCurrency(
                MONEY,
                MONEY_ICON,
                "currency.wathe.money",
                context -> shouldRenderBalanceHud(context.gameWorld(), context.player())
        );

        /*
         * 任务币 HUD 暂时暂停注册。
         *
         * 任务币作为杀手商店货币目前仍处于实验性阶段；默认杀手商店已经先恢复为纯金币价格，
         * 完成任务和击杀获得任务币的常量也暂时设为 0。因此这里不再把任务币注册进 HUD，
         * 避免杀手界面出现当前玩法用不到的货币板块。
         *
         * 后续如果重新启用任务币商店和任务币收益，可以恢复下面这段注册逻辑：
         *
         * registerCurrency(
         *         TASK_MONEY,
         *         TASK_MONEY_ICON,
         *         "currency.wathe.task_money",
         *         context -> context.gameWorld().canUseKillerFeatures(context.player())
         * );
         */
    }

    private EconomyApi() {
    }

    public static synchronized @NotNull CurrencyDefinition registerCurrency(
            @NotNull Identifier id,
            @NotNull String icon,
            @NotNull String translationKey,
            @NotNull CurrencyHudPredicate hudPredicate
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(translationKey, "translationKey");
        Objects.requireNonNull(hudPredicate, "hudPredicate");

        CurrencyDefinition definition = new CurrencyDefinition(id, icon, translationKey, nextCurrencyOrder++, hudPredicate);
        CURRENCIES.put(id, definition);
        return definition;
    }

    public static synchronized @Nullable CurrencyDefinition getCurrency(@NotNull Identifier id) {
        return CURRENCIES.get(id);
    }

    public static synchronized @NotNull List<CurrencyDefinition> currencySnapshot() {
        return List.copyOf(CURRENCIES.values());
    }

    public static @NotNull CurrencyDefinition getCurrencyOrFallback(@NotNull Identifier id) {
        CurrencyDefinition registered = getCurrency(id);
        if (registered != null) {
            return registered;
        }

        /*
         * 回放或扩展调试时可能遇到“记录里有货币 ID，但当前客户端没有注册对应定义”。
         * 这里提供一个只用于显示的兜底定义，避免文本直接崩掉。
         */
        String fallbackKey = "currency." + id.getNamespace() + "." + id.getPath();
        return new CurrencyDefinition(id, "", fallbackKey, Long.MAX_VALUE, context -> false);
    }

    public static @NotNull MutableText formatCurrencyAmount(@NotNull CurrencyAmount amount, boolean useIcon) {
        CurrencyDefinition definition = getCurrencyOrFallback(amount.currency());
        MutableText text = Text.literal(String.valueOf(amount.amount()));
        if (useIcon && !definition.icon().isEmpty()) {
            return text.append(definition.icon());
        }
        return text.append(" ").append(Text.translatable(definition.translationKey()));
    }

    public static @NotNull List<CurrencyBalance> getVisibleCurrencyBalances(@NotNull PlayerEntity player, boolean shopGoldFallback) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        Role role = gameWorld.getRole(player);
        PlayerShopComponent shop = PlayerShopComponent.KEY.get(player);
        List<CurrencyBalance> balances = new ArrayList<>();

        for (CurrencyDefinition currency : currencySnapshot()) {
            int amount = shop.getCurrencyAmount(currency.id());
            if (amount <= 0) {
                continue;
            }

            CurrencyHudContext context = new CurrencyHudContext(gameWorld, player, role, currency, amount);
            boolean shouldRender = currency.hudPredicate().shouldRender(context);

            /*
             * 兼容旧职业商店：某些扩展只注册了商店，没有额外注册金币 HUD 规则。
             * 这类纯金币商店仍然应该在玩家有金币时显示金币板块。
             */
            if (!shouldRender && shopGoldFallback && currency.id().equals(MONEY)) {
                shouldRender = true;
            }

            if (shouldRender) {
                balances.add(new CurrencyBalance(currency, amount));
            }
        }

        balances.sort(
                Comparator.<CurrencyBalance>comparingInt(CurrencyBalance::amount)
                        .reversed()
                        .thenComparingLong(balance -> balance.currency().order())
        );
        return balances;
    }

    public static synchronized void registerBalanceHudRole(@NotNull Role role) {
        BALANCE_HUD_ROLES.add(Objects.requireNonNull(role, "role"));
    }

    public static synchronized void registerBalanceHudRoles(@NotNull Collection<Role> roles) {
        for (Role role : roles) {
            registerBalanceHudRole(role);
        }
    }

    /**
     * 注册动态金币 HUD 判定。
     *
     * <p>这个入口给“是否显示金币 HUD 取决于配置或玩家状态”的职业使用。
     * 静态职业请优先用 {@link #registerBalanceHudRole(Role)}，这样更容易被其他联动逻辑读取。</p>
     */
    public static synchronized void registerBalanceHudPredicate(
            @NotNull Identifier id,
            int priority,
            @NotNull BalanceHudPredicate predicate
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(predicate, "predicate");
        BALANCE_HUD_PREDICATES.removeIf(entry -> entry.id().equals(id));
        BALANCE_HUD_PREDICATES.add(new PrioritizedBalanceHudPredicate(id, priority, nextOrder++, predicate));
        BALANCE_HUD_PREDICATES.sort(BALANCE_HUD_COMPARATOR);
    }

    public static boolean shouldRenderBalanceHud(@NotNull PlayerEntity player) {
        return shouldRenderBalanceHud(GameWorldComponent.KEY.get(player.getWorld()), player);
    }

    public static boolean shouldRenderBalanceHud(@NotNull GameWorldComponent gameWorld, @NotNull PlayerEntity player) {
        Role role = gameWorld.getRole(player);

        /*
         * 兼容 Wathe 原本行为：杀手能力角色默认可以看到金币 HUD。
         * 扩展职业只需要额外注册“非杀手但仍使用金币系统”的角色。
         */
        if (gameWorld.canUseKillerFeatures(player)) {
            return true;
        }
        if (role != null && hasRegisteredBalanceHudRole(role)) {
            return true;
        }

        for (PrioritizedBalanceHudPredicate entry : balanceHudPredicateSnapshot()) {
            if (entry.predicate().shouldRender(gameWorld, player, role)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean hasRegisteredBalanceHudRole(@NotNull Role role) {
        return BALANCE_HUD_ROLES.contains(role);
    }

    public static synchronized void registerPassiveIncomeRole(@NotNull Role role) {
        PASSIVE_INCOME_ROLES.add(Objects.requireNonNull(role, "role"));
    }

    public static synchronized void registerPassiveIncomeRoles(@NotNull Collection<Role> roles) {
        for (Role role : roles) {
            registerPassiveIncomeRole(role);
        }
    }

    /**
     * 注册被动收入资格规则。
     *
     * <p>规则返回 {@link PassiveIncomeDecision#PASS} 表示交给后续规则和默认逻辑；
     * 返回 ALLOW 表示强制允许本次通用被动收入；
     * 返回 DENY 表示强制禁止本次通用被动收入。</p>
     *
     * <p>DENY 主要给 Avaricious 这种特殊经济角色使用：它虽然拥有杀手商店能力，
     * 但普通被动收入应被关闭，金币来源由自己的特殊结算负责。</p>
     */
    public static synchronized void registerPassiveIncomeRule(
            @NotNull Identifier id,
            int priority,
            @NotNull PassiveIncomeRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        PASSIVE_INCOME_RULES.removeIf(entry -> entry.id().equals(id));
        PASSIVE_INCOME_RULES.add(new PrioritizedPassiveIncomeRule(id, priority, nextOrder++, rule));
        PASSIVE_INCOME_RULES.sort(PASSIVE_RULE_COMPARATOR);
    }

    public static boolean canReceivePassiveIncome(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player
    ) {
        Role role = gameWorld.getRole(player);
        PassiveIncomeEligibilityContext context = new PassiveIncomeEligibilityContext(world, gameWorld, player, role);

        for (PrioritizedPassiveIncomeRule entry : passiveIncomeRuleSnapshot()) {
            PassiveIncomeDecision decision = entry.rule().getDecision(context);
            if (decision == PassiveIncomeDecision.ALLOW) {
                return true;
            }
            if (decision == PassiveIncomeDecision.DENY) {
                return false;
            }
        }

        if (role != null && hasRegisteredPassiveIncomeRole(role)) {
            return true;
        }

        /*
         * 兼容 Wathe 原本行为：没有任何扩展规则处理时，杀手能力角色依然有通用被动收入。
         * 因此旧职业不会因为 API 化而丢失经济来源。
         */
        return gameWorld.canUseKillerFeatures(player);
    }

    public static synchronized boolean hasRegisteredPassiveIncomeRole(@NotNull Role role) {
        return PASSIVE_INCOME_ROLES.contains(role);
    }

    /**
     * 注册被动收入数值修改器。
     *
     * <p>修改器处理的是“尚未套用阵营金币上限”的本次基础收入。
     * 所有修改器算完后，Wathe 会统一调用 {@link GameConstants#getPassiveMoneyAmount}
     * 做上限裁剪，避免扩展模组重复实现上限逻辑或绕过上限。</p>
     */
    public static synchronized void registerPassiveIncomeModifier(
            @NotNull Identifier id,
            int priority,
            @NotNull PassiveIncomeModifier modifier
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        PASSIVE_INCOME_MODIFIERS.removeIf(entry -> entry.id().equals(id));
        PASSIVE_INCOME_MODIFIERS.add(new PrioritizedPassiveIncomeModifier(id, priority, nextOrder++, modifier));
        PASSIVE_INCOME_MODIFIERS.sort(PASSIVE_MODIFIER_COMPARATOR);
    }

    public static int calculatePassiveIncome(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player,
            int baseIncome
    ) {
        if (baseIncome <= 0 || !canReceivePassiveIncome(world, gameWorld, player)) {
            return 0;
        }

        Role role = gameWorld.getRole(player);
        PassiveIncomeContext context = new PassiveIncomeContext(world, gameWorld, player, role, baseIncome);
        int modifiedIncome = baseIncome;
        for (PrioritizedPassiveIncomeModifier entry : passiveIncomeModifierSnapshot()) {
            modifiedIncome = Math.max(0, entry.modifier().modifyIncome(context, modifiedIncome));
        }

        /*
         * 所有扩展只负责表达“这次收入应该是多少”，最终仍由 Wathe 统一按阵营上限裁剪。
         * 这样 Magnate 这类倍增效果不会在金币接近上限时造成溢出。
         */
        int currentBalance = PlayerShopComponent.KEY.get(player).getCurrencyAmount(MONEY);
        return GameConstants.getPassiveMoneyAmount(role == null ? null : role.getFaction(), currentBalance, modifiedIncome);
    }

    private static synchronized List<PrioritizedBalanceHudPredicate> balanceHudPredicateSnapshot() {
        return List.copyOf(BALANCE_HUD_PREDICATES);
    }

    private static synchronized List<PrioritizedPassiveIncomeRule> passiveIncomeRuleSnapshot() {
        return List.copyOf(PASSIVE_INCOME_RULES);
    }

    private static synchronized List<PrioritizedPassiveIncomeModifier> passiveIncomeModifierSnapshot() {
        return List.copyOf(PASSIVE_INCOME_MODIFIERS);
    }

    @FunctionalInterface
    public interface BalanceHudPredicate {
        boolean shouldRender(@NotNull GameWorldComponent gameWorld, @NotNull PlayerEntity player, @Nullable Role role);
    }

    @FunctionalInterface
    public interface CurrencyHudPredicate {
        boolean shouldRender(@NotNull CurrencyHudContext context);
    }

    @FunctionalInterface
    public interface PassiveIncomeRule {
        @NotNull PassiveIncomeDecision getDecision(@NotNull PassiveIncomeEligibilityContext context);
    }

    public enum PassiveIncomeDecision {
        PASS,
        ALLOW,
        DENY
    }

    @FunctionalInterface
    public interface PassiveIncomeModifier {
        int modifyIncome(@NotNull PassiveIncomeContext context, int currentIncome);
    }

    public record PassiveIncomeEligibilityContext(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player,
            @Nullable Role role
    ) {
    }

    public record PassiveIncomeContext(
            @NotNull ServerWorld world,
            @NotNull GameWorldComponent gameWorld,
            @NotNull ServerPlayerEntity player,
            @Nullable Role role,
            int baseIncome
    ) {
    }

    public record CurrencyHudContext(
            @NotNull GameWorldComponent gameWorld,
            @NotNull PlayerEntity player,
            @Nullable Role role,
            @NotNull CurrencyDefinition currency,
            int amount
    ) {
    }

    public record CurrencyBalance(
            @NotNull CurrencyDefinition currency,
            int amount
    ) {
    }

    private record PrioritizedBalanceHudPredicate(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull BalanceHudPredicate predicate
    ) {
    }

    private record PrioritizedPassiveIncomeRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull PassiveIncomeRule rule
    ) {
    }

    private record PrioritizedPassiveIncomeModifier(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull PassiveIncomeModifier modifier
    ) {
    }
}
