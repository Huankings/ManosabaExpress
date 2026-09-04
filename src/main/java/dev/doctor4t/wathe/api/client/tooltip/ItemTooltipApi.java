package dev.doctor4t.wathe.api.client.tooltip;

import dev.doctor4t.ratatouille.util.TextUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Wathe 物品描述与原版物品冷却读秒的客户端公开 API。
 *
 * <p>扩展只需要注册物品，Wathe 就会统一读取该物品的 {@code .tooltip} 翻译、按换行符拆行，
 * 并在物品存在原版 {@link ItemCooldownManager} 冷却时显示准确读秒。这里刻意不使用
 * {@code GameConstants.ITEM_COOLDOWNS} 或 {@link ItemCooldownManager#getCooldownProgress(Item, float)}
 * 反推剩余时间：同一个物品可能因职业、状态或 GunShotApi modifier 获得不同总冷却，客户端收到的
 * 当前冷却条目才是这一次真正生效的时长。</p>
 */
@Environment(EnvType.CLIENT)
public final class ItemTooltipApi {
    public static final int DEFAULT_PRIORITY = 0;
    public static final int COOLDOWN_COLOR = 0xC90000;
    public static final int LETTER_COLOR = 0xC5AE8B;
    public static final int REGULAR_TOOLTIP_COLOR = 0x808080;

    private static final Set<Item> STANDARD_ITEMS = new HashSet<>();
    private static final List<AppenderEntry> APPENDERS = new ArrayList<>();
    private static final Comparator<AppenderEntry> APPENDER_COMPARATOR =
            Comparator.<AppenderEntry>comparingInt(AppenderEntry::priority)
                    .thenComparingLong(AppenderEntry::order);

    private static boolean initialized = false;
    private static long nextOrder = 0L;

    private ItemTooltipApi() {
    }

    /**
     * 安装 Wathe 唯一的全局 tooltip callback。
     *
     * <p>注册 API 会自动调用本方法，因此扩展不需要关心客户端 entrypoint 的加载先后；
     * Wathe 本体仍会显式调用一次，方便从初始化入口直接看出 tooltip 系统已经启用。</p>
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ItemTooltipCallback.EVENT.register(ItemTooltipApi::appendRegisteredTooltips);
    }

    /** 注册一个使用 Wathe 标准“冷却读秒 + 多行本地化描述”的物品。 */
    public static synchronized void registerItem(@NotNull Item item) {
        Objects.requireNonNull(item, "item");
        initialize();
        STANDARD_ITEMS.add(item);
    }

    /** 批量注册使用 Wathe 标准 tooltip 的物品，避免扩展重复编写逐项判断 callback。 */
    public static synchronized void registerItems(@NotNull Item... items) {
        Objects.requireNonNull(items, "items");
        initialize();
        Arrays.stream(items).forEach(item -> STANDARD_ITEMS.add(Objects.requireNonNull(item, "item")));
    }

    /**
     * 为指定物品注册额外动态文本。
     *
     * <p>Appender 在标准冷却和描述之后执行；priority 越小越先追加，同 priority 下先注册者先执行。
     * 这个入口适合展示物品数据组件、职业组件或动态数值，不应用来重新实现标准冷却读秒。</p>
     */
    public static synchronized void registerAppender(@NotNull Identifier id,
                                                     int priority,
                                                     @NotNull Item item,
                                                     @NotNull TooltipAppender appender) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(appender, "appender");
        initialize();
        APPENDERS.removeIf(entry -> entry.id().equals(id));
        APPENDERS.add(new AppenderEntry(id, priority, nextOrder++, item, appender));
        APPENDERS.sort(APPENDER_COMPARATOR);
    }

    /**
     * 读取玩家客户端当前这一次物品冷却的准确剩余 tick。
     *
     * <p>{@code endTick - tick} 直接来自服务端同步后建立的当前条目，所以不论初始冷却、动态枪械冷却、
     * 临时冷却还是覆盖后的冷却都无需额外同步“来源类型”。没有玩家、没有条目或已经结束时返回 0。</p>
     */
    public static int getRemainingCooldownTicks(@Nullable PlayerEntity player, @NotNull Item item) {
        Objects.requireNonNull(item, "item");
        if (player == null) {
            return 0;
        }
        ItemCooldownManager manager = player.getItemCooldownManager();
        ItemCooldownManager.Entry entry = manager.entries.get(item);
        return entry == null ? 0 : Math.max(0, entry.endTick - manager.tick);
    }

    /**
     * 把 tick 格式化成现有 tooltip 使用的紧凑时间，例如 {@code 6s}、{@code 1m5s}。
     * 不足一秒但仍在冷却时向上显示为 1 秒，避免最后十九 tick 出现空白倒计时。
     */
    public static @NotNull String formatCooldownTicks(int remainingTicks) {
        if (remainingTicks <= 0) {
            return "";
        }
        int totalSeconds = Math.max(1, (remainingTicks + 19) / 20);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return (minutes > 0 ? minutes + "m" : "") + (seconds > 0 ? seconds + "s" : "");
    }

    private static void appendRegisteredTooltips(ItemStack stack,
                                                 Item.TooltipContext tooltipContext,
                                                 TooltipType tooltipType,
                                                 List<Text> tooltip) {
        Item item = stack.getItem();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (standardItemSnapshot().contains(item)) {
            int remainingTicks = getRemainingCooldownTicks(player, item);
            if (remainingTicks > 0) {
                tooltip.add(Text.translatable("tip.cooldown", formatCooldownTicks(remainingTicks))
                        .withColor(COOLDOWN_COLOR));
            }
            tooltip.addAll(TextUtils.getTooltipForItem(item, Style.EMPTY.withColor(REGULAR_TOOLTIP_COLOR)));
        }

        Context context = new Context(client, player, stack, tooltipContext, tooltipType, tooltip);
        for (AppenderEntry entry : appenderSnapshot()) {
            if (entry.item() == item) {
                entry.appender().append(context);
            }
        }
    }

    private static synchronized Set<Item> standardItemSnapshot() {
        return Set.copyOf(STANDARD_ITEMS);
    }

    private static synchronized List<AppenderEntry> appenderSnapshot() {
        return List.copyOf(APPENDERS);
    }

    /** 动态附加文本所需的完整客户端上下文；扩展直接向 tooltip 列表追加 Text。 */
    public record Context(@NotNull MinecraftClient client,
                          @Nullable ClientPlayerEntity player,
                          @NotNull ItemStack stack,
                          @NotNull Item.TooltipContext tooltipContext,
                          @NotNull TooltipType tooltipType,
                          @NotNull List<Text> tooltip) {
    }

    @FunctionalInterface
    public interface TooltipAppender {
        void append(@NotNull Context context);
    }

    private record AppenderEntry(@NotNull Identifier id,
                                 int priority,
                                 long order,
                                 @NotNull Item item,
                                 @NotNull TooltipAppender appender) {
    }
}
