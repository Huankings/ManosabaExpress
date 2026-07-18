package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.api.economy.CurrencyAmount;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 商店价格表达式。
 *
 * <p>内部模型是“多个可选支付方案 OR”，每个支付方案里又可以包含“多个货币 AND”。
 * 例如：</p>
 * <p>1. 50 金币 + 25 任务币：{@code ShopPrice.allOf(money(50), taskMoney(25))}</p>
 * <p>2. 100 金币 或 50 任务币：{@code ShopPrice.anyOf(option(money(100)), option(taskMoney(50)))}</p>
 *
 * <p>选择扣款方案时使用你确认的规则：先选“所有货币数量相加最少”的可支付方案；
 * 如果总和相同，再按价格定义顺序选择更靠前的方案。</p>
 */
public final class ShopPrice {
    private static final Comparator<IndexedOption> PAYMENT_COMPARATOR =
            Comparator.comparingInt((IndexedOption indexed) -> indexed.option().totalAmount())
                    .thenComparingInt(IndexedOption::index);

    private final List<Option> options;

    private ShopPrice(@NotNull List<Option> options) {
        this.options = List.copyOf(options);
    }

    public static @NotNull ShopPrice money(int amount) {
        return allOf(CurrencyAmount.money(amount));
    }

    public static @NotNull ShopPrice allOf(@NotNull CurrencyAmount... costs) {
        return new ShopPrice(List.of(option(costs)));
    }

    public static @NotNull ShopPrice anyOf(@NotNull Option... options) {
        List<Option> list = new ArrayList<>();
        for (Option option : options) {
            list.add(Objects.requireNonNull(option, "option"));
        }
        return new ShopPrice(list);
    }

    public static @NotNull Option option(@NotNull CurrencyAmount... costs) {
        return new Option(List.of(costs));
    }

    public @NotNull List<Option> options() {
        return this.options;
    }

    /**
     * 旧 API 的“单个 int 价格”兼容值。
     *
     * <p>旧扩展模组还会调用 {@code entry.price()}。对于纯金币商品，这里会返回原本的金币数；
     * 对于多货币商品，优先返回第一个方案里的金币数量，没有金币时才退回到该方案总和。</p>
     */
    public int legacyPrice() {
        if (this.options.isEmpty()) {
            return 0;
        }

        Option first = this.options.getFirst();
        for (CurrencyAmount cost : first.costs()) {
            if (cost.currency().equals(EconomyApi.MONEY)) {
                return cost.amount();
            }
        }
        return first.totalAmount();
    }

    public boolean canAfford(@NotNull PlayerShopComponent shop) {
        return this.selectPayment(shop) != null;
    }

    public @Nullable ShopPayment selectPayment(@NotNull PlayerShopComponent shop) {
        List<IndexedOption> affordable = new ArrayList<>();
        for (int i = 0; i < this.options.size(); i++) {
            Option option = this.options.get(i);
            if (option.canAfford(shop)) {
                affordable.add(new IndexedOption(i, option));
            }
        }
        if (affordable.isEmpty()) {
            return null;
        }

        affordable.sort(PAYMENT_COMPARATOR);
        IndexedOption selected = affordable.getFirst();
        return ShopPayment.of(selected.index(), selected.option().costs());
    }

    /**
     * 开发环境兜底用：当测试时余额不足，给玩家补齐最便宜的一组方案。
     */
    public @Nullable ShopPayment cheapestPaymentForDevelopment() {
        List<IndexedOption> indexed = new ArrayList<>();
        for (int i = 0; i < this.options.size(); i++) {
            indexed.add(new IndexedOption(i, this.options.get(i)));
        }
        if (indexed.isEmpty()) {
            return null;
        }

        indexed.sort(PAYMENT_COMPARATOR);
        IndexedOption selected = indexed.getFirst();
        return ShopPayment.of(selected.index(), selected.option().costs());
    }

    public @NotNull List<Text> displayLines() {
        List<Text> lines = new ArrayList<>();
        for (int optionIndex = 0; optionIndex < this.options.size(); optionIndex++) {
            if (optionIndex > 0) {
                lines.add(Text.translatable("shop.price.or"));
            }

            Option option = this.options.get(optionIndex);
            if (option.costs().isEmpty()) {
                lines.add(Text.translatable("shop.price.free"));
                continue;
            }

            for (CurrencyAmount cost : option.costs()) {
                lines.add(EconomyApi.formatCurrencyAmount(cost, true));
            }
        }
        return lines;
    }

    public record Option(@NotNull List<CurrencyAmount> costs) {
        public Option {
            Objects.requireNonNull(costs, "costs");
            List<CurrencyAmount> filtered = new ArrayList<>();
            for (CurrencyAmount cost : costs) {
                Objects.requireNonNull(cost, "cost");
                if (cost.amount() > 0) {
                    filtered.add(cost);
                }
            }
            costs = List.copyOf(filtered);
        }

        public int totalAmount() {
            int total = 0;
            for (CurrencyAmount cost : this.costs) {
                total += cost.amount();
            }
            return total;
        }

        private boolean canAfford(@NotNull PlayerShopComponent shop) {
            for (CurrencyAmount cost : this.costs) {
                if (shop.getCurrencyAmount(cost.currency()) < cost.amount()) {
                    return false;
                }
            }
            return true;
        }
    }

    private record IndexedOption(int index, @NotNull Option option) {
    }
}
