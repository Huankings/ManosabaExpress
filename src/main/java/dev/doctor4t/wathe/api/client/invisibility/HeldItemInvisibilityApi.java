package dev.doctor4t.wathe.api.client.invisibility;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wathe 的手持物品不可见公开接口。
 *
 * <p>这个 API 只负责“描述规则”，不负责具体渲染：
 * 1. 扩展模组注册“某职业 + 某物品”时，这个物品对其他局内存活玩家不可见；
 * 2. 扩展模组也可以注册“某种状态下任意物品都不可见”的动态规则；
 * 3. Wathe 自己在渲染层统一判断可见性，并确保本地玩家 F5 看自己时仍然能看到自己的手持物。</p>
 */
@Environment(EnvType.CLIENT)
public final class HeldItemInvisibilityApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<PrioritizedVisibilityRule> RULE_COMPARATOR =
            Comparator.<PrioritizedVisibilityRule>comparingInt(PrioritizedVisibilityRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedVisibilityRule::order).reversed());

    /**
     * 这里保存的是“职业 -> 需要隐藏的物品集合”。
     *
     * <p>它覆盖最常见的“主动手持某个专属物品时，对其他玩家不可见”的需求，
     * 这样扩展职业 mod 不需要自己再写任何渲染 mixin。</p>
     */
    private static final Map<Role, Set<Item>> ROLE_HIDDEN_ITEMS = new HashMap<>();

    /**
     * 动态隐藏规则给“附体 / 控制 / 变身”等需要按状态决定的场景使用。
     * 规则按优先级从高到低排序，谁先命中谁先生效。</p>
     */
    private static final List<PrioritizedVisibilityRule> VISIBILITY_RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private HeldItemInvisibilityApi() {
    }

    /**
     * 注册“某职业拿着某物品时不可见”的常规规则。
     *
     * <p>这是最常见、也是最推荐的接入方式。
     * 例如：{@code registerHiddenItem(TECHNICIAN, CAPTURE_DEVICE)}。</p>
     */
    public static synchronized void registerHiddenItem(@NotNull Role role, @NotNull Item item) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(item, "item");
        ROLE_HIDDEN_ITEMS.computeIfAbsent(role, ignored -> new HashSet<>()).add(item);
    }

    /**
     * 批量注册同一职业的多个隐藏物品。
     *
     * <p>如果某个职业有主手/副手多个专属物品，可以统一放进来，
     * 省得在扩展模组里写一串重复的调用。</p>
     */
    public static synchronized void registerHiddenItems(@NotNull Role role, @NotNull Collection<Item> items) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(items, "items");
        for (Item item : items) {
            registerHiddenItem(role, item);
        }
    }

    /**
     * 动态注册一个隐藏规则。
     *
     * <p>这个入口专门给“控制中无论手里拿什么都隐藏”之类的场景准备。
     * 如果未来还有更复杂的状态型隐藏逻辑，也都可以接到这里。</p>
     */
    public static synchronized void registerRule(
            @NotNull Identifier id,
            int priority,
            @NotNull VisibilityRule rule
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        VISIBILITY_RULES.removeIf(entry -> entry.id().equals(id));
        VISIBILITY_RULES.add(new PrioritizedVisibilityRule(id, priority, nextOrder++, rule));
        VISIBILITY_RULES.sort(RULE_COMPARATOR);
    }

    /**
     * 把“是否隐藏”的结果应用到一个手持物品上。
     *
     * <p>渲染层只需要调用这个方法，就能自动兼容：
     * 1. 职业专属隐藏；
     * 2. 动态状态隐藏；
     * 3. 本地玩家自视角放行；
     * 4. 低心情幻觉逻辑后续覆盖。</p>
     */
    public static ItemStack applyInvisibility(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack stack
    ) {
        return shouldHideFromOtherLivingPlayers(viewer, holder, hand, stack) ? ItemStack.EMPTY : stack;
    }

    public static ItemStack applyInvisibility(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand
    ) {
        return applyInvisibility(viewer, holder, hand, holder.getStackInHand(hand));
    }

    /**
     * 判断某个手持物品在“其他局内存活玩家视角”下是否需要隐藏。
     *
     * <p>这里会先排除掉几个不该隐藏的情况：
     * 1. 持有者不是玩家；
     * 2. 持有者本身已经不是局内存活玩家；
     * 3. 观察者不是局内存活玩家；
     * 4. 观察者就是持有者自己（F5 看自己时要保留可见）。</p>
     */
    public static boolean shouldHideFromOtherLivingPlayers(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack stack
    ) {
        if (stack.isEmpty() || !(holder instanceof PlayerEntity holderPlayer)) {
            return false;
        }

        // 只有“局内仍然活着”的持有者才参与这套规则。
        if (!GameFunctions.isPlayerAliveAndSurvival(holderPlayer)) {
            return false;
        }

        // 本地玩家自己看自己时必须保留手持物可见，F5 也算在这里。
        if (viewer == null || viewer.getUuid().equals(holderPlayer.getUuid())) {
            return false;
        }

        // 只对其他“局内仍然活着”的观察者隐藏，死亡/旁观/创造视角保持可见。
        if (!GameFunctions.isPlayerAliveAndSurvival(viewer)) {
            return false;
        }

        return matchesInvisibleRule(holderPlayer, hand, stack);
    }

    public static boolean shouldHideFromOtherLivingPlayers(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand
    ) {
        return shouldHideFromOtherLivingPlayers(viewer, holder, hand, holder.getStackInHand(hand));
    }

    /**
     * 判断某个玩家当前手里拿的东西，是否已经命中了“会对其他人隐藏”的规则。
     *
     * <p>这个方法不给“观察者是谁”加限制，专门给 actionbar 提示用。
     * 提示只要知道“这只手上的东西对别人会不会隐形”即可。</p>
     */
    public static boolean isHiddenByAnyRule(@NotNull PlayerEntity holder, @NotNull Hand hand) {
        return isHiddenByAnyRule(holder, hand, holder.getStackInHand(hand));
    }

    public static boolean isHiddenByAnyRule(
            @NotNull PlayerEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack stack
    ) {
        if (stack.isEmpty() || !GameFunctions.isPlayerAliveAndSurvival(holder)) {
            return false;
        }
        return matchesInvisibleRule(holder, hand, stack);
    }

    /**
     * 判断玩家主手和副手里，是否至少有一个命中了隐藏规则。
     *
     * <p>Wathe 会用这个结果来控制 actionbar 提示：
     * 只在“玩家刚刚拿出会隐形的物品”那一刻提示一次，避免每 tick 刷屏。</p>
     */
    public static boolean hasHiddenHeldItem(@NotNull PlayerEntity holder) {
        return isHiddenByAnyRule(holder, Hand.MAIN_HAND) || isHiddenByAnyRule(holder, Hand.OFF_HAND);
    }

    /**
     * 判断玩家主手和副手里，是否至少有一个命中了“主动职业物品隐藏”规则。
     *
     * <p>这和 {@link #hasHiddenHeldItem(PlayerEntity)} 的区别是：
     * 它不会把 Controlled 这种“被动隐藏任意物品”的动态规则算进去。
     * Wathe 的 actionbar 提示只使用这个方法，因为提示语义是“玩家主动拿出了一个隐形物品”。</p>
     */
    public static boolean hasActiveHiddenHeldItem(@NotNull PlayerEntity holder) {
        return isActiveHiddenItem(holder, Hand.MAIN_HAND) || isActiveHiddenItem(holder, Hand.OFF_HAND);
    }

    public static boolean isActiveHiddenItem(@NotNull PlayerEntity holder, @NotNull Hand hand) {
        ItemStack stack = holder.getStackInHand(hand);
        if (stack.isEmpty() || !GameFunctions.isPlayerAliveAndSurvival(holder)) {
            return false;
        }

        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(holder.getWorld());
        if (!gameWorld.isRunning()) {
            return false;
        }

        Role role = gameWorld.getRole(holder);
        return role != null && hasRegisteredHiddenItem(role, stack.getItem());
    }

    private static boolean matchesInvisibleRule(
            @NotNull PlayerEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack stack
    ) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(holder.getWorld());
        if (!gameWorld.isRunning()) {
            return false;
        }
        Role role = gameWorld.getRole(holder);

        // 先跑动态规则，给“控制中整只手都不可见”这种状态型逻辑优先机会。
        for (PrioritizedVisibilityRule entry : visibilityRuleSnapshot()) {
            if (entry.rule().shouldHide(new VisibilityContext(gameWorld, holder, hand, stack, role))) {
                return true;
            }
        }

        // 再跑最常见的“职业 + 物品”静态规则。
        return role != null && hasRegisteredHiddenItem(role, stack.getItem());
    }

    private static synchronized boolean hasRegisteredHiddenItem(@NotNull Role role, @NotNull Item item) {
        Set<Item> items = ROLE_HIDDEN_ITEMS.get(role);
        return items != null && items.contains(item);
    }

    private static synchronized List<PrioritizedVisibilityRule> visibilityRuleSnapshot() {
        return List.copyOf(VISIBILITY_RULES);
    }

    @FunctionalInterface
    public interface VisibilityRule {
        boolean shouldHide(@NotNull VisibilityContext context);
    }

    public record VisibilityContext(
            @NotNull GameWorldComponent gameWorld,
            @NotNull PlayerEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack stack,
            @Nullable Role role
    ) {
    }

    private record PrioritizedVisibilityRule(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull VisibilityRule rule
    ) {
    }
}
