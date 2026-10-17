package dev.doctor4t.wathe.api.item;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.index.tag.WatheItemTags;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 普通 {@link ItemEntity} 是否允许被玩家拾取的服务端公开 API。
 *
 * <p>扩展可以用 {@link ItemPickupResult#DENY} 禁止特定职业或状态拾取掉落物，
 * 也可以用 {@link ItemPickupResult#ALLOW} 覆盖 Wathe 的默认枪械拾取限制。
 * ALLOW 并不是直接把物品塞进背包：它只允许进入原版方法，所以拾取延迟、
 * owner 限制、背包空间、统计、进度准则和 Wathe 回放仍由原流程统一处理。</p>
 *
 * <p>priority 越大越先执行；同 priority 下后注册的规则先执行。
 * 第一个非 PASS 结果立即结束判定，因此“重伤/强制缴械”类规则应使用
 * 高于普通职业拾取特权的优先级。</p>
 */
public final class ItemPickupApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());
    private static final List<Entry> RULES = new ArrayList<>();
    private static long nextOrder = 0L;

    private ItemPickupApi() {
    }

    public static void registerRule(@NotNull Identifier id, int priority, @NotNull Rule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (RULES) {
            RULES.removeIf(entry -> entry.id().equals(id));
            RULES.add(new Entry(id, priority, nextOrder++, rule));
            RULES.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 判定该玩家是否可以进入原版拾取方法。
     *
     * <p>本 API 只针对普通 ItemEntity，不包含箭、三叉戟、飞斧等自定义投射物的 tryPickup。</p>
     */
    public static boolean canPickup(@NotNull ServerPlayerEntity player, @NotNull ItemEntity itemEntity) {
        GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
        boolean watheDefaultAllows = resolveWatheDefault(player, itemEntity, game);
        ItemPickupContext context = new ItemPickupContext(
                player,
                itemEntity,
                itemEntity.getStack().copy(),
                itemEntity.getOwner(),
                game,
                watheDefaultAllows
        );

        for (Entry entry : snapshot()) {
            ItemPickupResult result = entry.rule().resolve(context);
            if (result == ItemPickupResult.DENY) {
                return false;
            }
            if (result == ItemPickupResult.ALLOW) {
                return true;
            }
        }
        return watheDefaultAllows;
    }

    /**
     * Wathe 现有枪械拾取规则的集中实现。
     *
     * <p>创造玩家和非枪械始终放行；普通玩家拾取枪械时，必须是 Wathe 认定的
     * innocent，不是该掉落物的 owner，并且背包中还没有其它枪械。</p>
     */
    private static boolean resolveWatheDefault(
            @NotNull ServerPlayerEntity player,
            @NotNull ItemEntity itemEntity,
            @NotNull GameWorldComponent game
    ) {
        if (player.isCreative() || !itemEntity.getStack().isIn(WatheItemTags.GUNS)) {
            return true;
        }
        return game.isInnocent(player)
                && !player.equals(itemEntity.getOwner())
                && !player.getInventory().contains(stack -> stack.isIn(WatheItemTags.GUNS));
    }

    private static List<Entry> snapshot() {
        synchronized (RULES) {
            return List.copyOf(RULES);
        }
    }

    @FunctionalInterface
    public interface Rule {
        @NotNull ItemPickupResult resolve(@NotNull ItemPickupContext context);
    }

    private record Entry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull Rule rule
    ) {
    }
}
