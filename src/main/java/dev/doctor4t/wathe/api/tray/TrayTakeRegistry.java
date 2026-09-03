package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 托盘取物限制公开接口。
 *
 * <p>返回值表示玩家手中和背包中同类物品允许保留的最大总数：
 * {@code null} 表示本规则不关心当前物品，{@code 0} 表示禁止取出，正数表示覆盖默认上限。
 * 规则按优先级从高到低询问，首个明确结果生效。FoodPlatterBlock 会把本次托盘候选物品得到的最大值作为
 * “该玩家对该托盘的总取物次数上限”，因此托盘内多个物品不会各自重复获得一轮次数。</p>
 */
public final class TrayTakeRegistry {
    @FunctionalInterface
    public interface Rule {
        @Nullable Integer maxHeldCount(TrayTakeContext context);
    }

    @FunctionalInterface
    public interface GroupRule {
        @Nullable TrayTakeDecision decision(TrayTakeContext context);
    }

    private record Entry(String id, int priority, Rule rule) {
    }

    private record GroupEntry(String id, int priority, GroupRule rule) {
    }

    private static final List<Entry> RULES = new ArrayList<>();
    private static final List<GroupEntry> GROUP_RULES = new ArrayList<>();

    private TrayTakeRegistry() {
    }

    public static void registerRule(String id, int priority, Rule rule) {
        RULES.removeIf(entry -> entry.id().equals(id));
        RULES.add(new Entry(id, priority, rule));
        RULES.sort(Comparator.comparingInt(Entry::priority).reversed());
    }

    /** 注册按托盘候选物品分组计算的规则。 */
    public static void registerGroupRule(String id, int priority, GroupRule rule) {
        GROUP_RULES.removeIf(entry -> entry.id().equals(id));
        GROUP_RULES.add(new GroupEntry(id, priority, rule));
        GROUP_RULES.sort(Comparator.comparingInt(GroupEntry::priority).reversed());
    }

    @Nullable
    public static TrayTakeDecision resolveGroupDecision(ServerPlayerEntity player, BeveragePlateBlockEntity plate, ItemStack candidate) {
        TrayTakeContext context = new TrayTakeContext(player, plate, candidate);
        for (GroupEntry entry : GROUP_RULES) {
            TrayTakeDecision decision = entry.rule().decision(context);
            if (decision != null) {
                return decision;
            }
        }
        return null;
    }

    public static int resolveMaxHeldCount(ServerPlayerEntity player, BeveragePlateBlockEntity plate, ItemStack candidate) {
        TrayTakeContext context = new TrayTakeContext(player, plate, candidate);
        for (Entry entry : RULES) {
            Integer result = entry.rule().maxHeldCount(context);
            if (result != null) {
                return Math.max(0, result);
            }
        }
        return 1;
    }
}
