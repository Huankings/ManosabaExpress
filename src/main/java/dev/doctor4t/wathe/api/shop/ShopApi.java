package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.economy.CurrencyAmount;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wathe 商店系统的公开接入点。
 *
 * <p>这个 API 负责把“当前玩家能看到哪些商品”和“购买时如何交付商品”
 * 从 Wathe 固定杀手商店里抽出来。扩展职业模组注册到这里后，客户端界面、
 * 服务端购买、失败提示、音效、扣钱和回放记录都会走同一套 Wathe 流程。</p>
 */
public final class ShopApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final RoleShopProvider DEFAULT_PROVIDER = player -> GameConstants.SHOP_ENTRIES;
    private static final Map<Role, RoleShopProvider> ROLE_SHOPS = new HashMap<>();
    private static final List<PrioritizedShopModifier> MODIFIERS = new ArrayList<>();
    private static final Comparator<PrioritizedShopModifier> MODIFIER_COMPARATOR =
            Comparator.<PrioritizedShopModifier>comparingInt(PrioritizedShopModifier::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedShopModifier::order).reversed());
    private static long nextOrder = 0L;

    private ShopApi() {
    }

    /**
     * 为单个职业注册专属商店。
     *
     * <p>注册后，这个职业将不再读取 Wathe 原版固定杀手商店，而是读取 provider 返回的列表。
     * 这就是 NoellesRoles、StupidExpress、KinsWathe 等扩展后续替代商店 mixin 的入口。</p>
     */
    public static synchronized void registerRoleShop(@NotNull Role role, @NotNull RoleShopProvider provider) {
        ROLE_SHOPS.put(Objects.requireNonNull(role, "role"), Objects.requireNonNull(provider, "provider"));
    }

    /**
     * 注册一个静态商品列表。
     *
     * <p>这里仍然使用 {@link Supplier}，而不是直接存死列表，是为了兼容某些扩展在初始化后
     * 才继续追加商品的旧结构；每次打开商店时都会重新取一次列表。</p>
     */
    public static synchronized void registerStaticRoleShop(@NotNull Role role, @NotNull Supplier<List<ShopEntry>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        registerRoleShop(role, player -> supplier.get());
    }

    public static synchronized void registerStaticRoleShop(@NotNull Supplier<List<ShopEntry>> supplier, @NotNull Role... roles) {
        for (Role role : roles) {
            registerStaticRoleShop(role, supplier);
        }
    }

    public static synchronized void registerStaticRoleShops(@NotNull Collection<Role> roles, @NotNull Supplier<List<ShopEntry>> supplier) {
        for (Role role : roles) {
            registerStaticRoleShop(role, supplier);
        }
    }

    /**
     * 注册通用商店修改器。
     *
     * <p>修改器适合做“小改动”：追加商品、按职业替换价格、移除某个默认商品等。
     * 如果某个职业需要完全不同的一整套商品，优先用 {@link #registerRoleShop(Role, RoleShopProvider)}。</p>
     */
    public static synchronized void registerShopModifier(
            @NotNull Identifier id,
            int priority,
            @NotNull ShopModifier modifier
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifier, "modifier");
        MODIFIERS.removeIf(entry -> entry.id().equals(id));
        MODIFIERS.add(new PrioritizedShopModifier(id, priority, nextOrder++, modifier));
        MODIFIERS.sort(MODIFIER_COMPARATOR);
    }

    public static @NotNull List<ShopEntry> getEntriesForPlayer(@NotNull PlayerEntity player) {
        return resolveShop(player).entries();
    }

    /**
     * 判断玩家是否拥有可显示的商店。
     *
     * <p>金币 HUD 可以用这个判断作为兜底：非杀手职业只要注册了商店，也应该能看到余额。</p>
     */
    public static boolean hasShop(@NotNull PlayerEntity player) {
        return !getEntriesForPlayer(player).isEmpty();
    }

    public static synchronized boolean hasRoleShop(@NotNull Role role) {
        return ROLE_SHOPS.containsKey(role);
    }

    public static @NotNull ResolvedShop resolveShop(@NotNull PlayerEntity player) {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(player.getWorld());
        Role role = gameWorld.getRole(player);
        RoleShopProvider roleProvider = getProviderForRole(role);
        boolean roleSpecificShop = roleProvider != null;

        RoleShopProvider provider;
        List<ShopEntry> entries;
        if (roleProvider != null) {
            provider = roleProvider;
            entries = copyEntries(roleProvider.getShopEntries(player));
        } else if (gameWorld.canUseKillerFeatures(player)) {
            provider = DEFAULT_PROVIDER;
            entries = copyEntries(GameConstants.SHOP_ENTRIES);
        } else {
            provider = DEFAULT_PROVIDER;
            entries = new ArrayList<>();
        }

        ShopContext context = new ShopContext(player, gameWorld, role, roleSpecificShop);
        for (PrioritizedShopModifier entry : modifierSnapshot()) {
            entry.modifier().modify(context, entries);
        }

        return new ResolvedShop(provider, List.copyOf(entries), gameWorld, role, roleSpecificShop);
    }

    public static @NotNull ShopPurchaseResult defaultPurchase(@NotNull ShopPurchaseContext context) {
        PlayerEntity player = context.player();
        ShopEntry entry = context.entry();
        if (!entry.shopPrice().canAfford(context.shop()) || player.getItemCooldownManager().isCoolingDown(entry.stack().getItem())) {
            return ShopPurchaseResult.FAIL_SHOW_MESSAGE;
        }

        /*
         * 这里只判断商品交付是否成功；扣钱、音效和回放记录在 PlayerShopComponent 里统一完成。
         * 这样扩展职业即使覆写购买逻辑，也不会绕过 Wathe 的公共结算流程。
         */
        if (entry.onBuy(player)) {
            return ShopPurchaseResult.SUCCESS;
        }
        return entry.shouldShowPurchaseFailedMessage(player)
                ? ShopPurchaseResult.FAIL_SHOW_MESSAGE
                : ShopPurchaseResult.FAIL_SILENT;
    }

    public static int getDefaultPrice(@NotNull Item item, int fallback) {
        for (ShopEntry entry : GameConstants.SHOP_ENTRIES) {
            if (entry.stack().isOf(item)) {
                return entry.price();
            }
        }
        return fallback;
    }

    /**
     * 读取 Wathe 默认商店中某个物品的完整价格结构。
     *
     * <p>这个方法会返回整套 {@link ShopPrice}：包含所有货币、AND 条件和 OR 支付方案。
     * 扩展职业只有在“明确希望完整继承默认杀手商品价格”时才应该使用它。
     * 如果只是想取某一种货币的某一组价格，请使用
     * {@link #getDefaultCurrencyPrice(Item, int, Identifier, int)}，避免中立/平民商店误带任务币。</p>
     */
    public static @Nullable ShopPrice getDefaultShopPrice(@NotNull Item item) {
        for (ShopEntry entry : GameConstants.SHOP_ENTRIES) {
            if (entry.stack().isOf(item)) {
                return entry.shopPrice();
            }
        }
        return null;
    }

    /**
     * 按“支付方案索引 + 货币 id”读取默认商店价格。
     *
     * <p>例如如果后续重新启用疯魔模式的任务币实验方案，它可能有两组支付方案：</p>
     * <p>option 0: 350 金币 + 25 任务币</p>
     * <p>option 1: 300 金币 + 75 任务币</p>
     * <p>调用方可以分别读取 option 0 的金币、option 0 的任务币、option 1 的金币、option 1 的任务币，
     * 而不是把整套价格直接复制到自己的商店里。当前 Wathe 默认疯魔模式是纯金币，读取不存在的 option
     * 或不存在的货币时会返回调用方传入的 fallback。</p>
     */
    public static int getDefaultCurrencyPrice(
            @NotNull Item item,
            int optionIndex,
            @NotNull Identifier currency,
            int fallback
    ) {
        ShopPrice price = getDefaultShopPrice(item);
        if (price == null || optionIndex < 0 || optionIndex >= price.options().size()) {
            return fallback;
        }

        for (CurrencyAmount cost : price.options().get(optionIndex).costs()) {
            if (cost.currency().equals(currency)) {
                return cost.amount();
            }
        }
        return fallback;
    }

    public static int getDefaultMoneyPrice(@NotNull Item item, int optionIndex, int fallback) {
        return getDefaultCurrencyPrice(item, optionIndex, EconomyApi.MONEY, fallback);
    }

    public static int getDefaultTaskMoneyPrice(@NotNull Item item, int optionIndex, int fallback) {
        return getDefaultCurrencyPrice(item, optionIndex, EconomyApi.TASK_MONEY, fallback);
    }

    public static void sendPurchaseFailedMessage(@NotNull PlayerEntity player) {
        player.sendMessage(Text.translatable("shop.purchase_failed").withColor(0xAA0000), true);
    }

    public static void playBuySound(@NotNull PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.playSoundToPlayer(
                    WatheSounds.UI_SHOP_BUY,
                    SoundCategory.PLAYERS,
                    1.0F,
                    0.9F + player.getRandom().nextFloat() * 0.2F
            );
        }
    }

    public static void playFailSound(@NotNull PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.playSoundToPlayer(
                    WatheSounds.UI_SHOP_BUY_FAIL,
                    SoundCategory.PLAYERS,
                    1.0F,
                    0.9F + player.getRandom().nextFloat() * 0.2F
            );
        }
    }

    private static synchronized @Nullable RoleShopProvider getProviderForRole(@Nullable Role role) {
        return role == null ? null : ROLE_SHOPS.get(role);
    }

    private static synchronized List<PrioritizedShopModifier> modifierSnapshot() {
        return List.copyOf(MODIFIERS);
    }

    private static @NotNull List<ShopEntry> copyEntries(@Nullable List<ShopEntry> entries) {
        return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public record ResolvedShop(
            @NotNull RoleShopProvider provider,
            @NotNull List<ShopEntry> entries,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            boolean roleSpecificShop
    ) {
    }

    private record PrioritizedShopModifier(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull ShopModifier modifier
    ) {
    }
}
