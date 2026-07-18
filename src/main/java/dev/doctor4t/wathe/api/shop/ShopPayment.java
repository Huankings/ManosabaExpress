package dev.doctor4t.wathe.api.shop;

import dev.doctor4t.wathe.api.economy.CurrencyAmount;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * 一次实际选择并扣除的支付方案。
 *
 * <p>例如“匕首：100 金币 或 50 任务币”会先被 {@link ShopPrice}
 * 解析为两个可选方案；玩家购买成功后，最终只会落到这里的一个方案。</p>
 */
public record ShopPayment(int optionIndex, @NotNull List<CurrencyAmount> costs) {
    public ShopPayment {
        Objects.requireNonNull(costs, "costs");
        costs = List.copyOf(costs);
    }

    public static @NotNull ShopPayment of(int optionIndex, @NotNull List<CurrencyAmount> costs) {
        return new ShopPayment(optionIndex, costs);
    }

    public static @NotNull ShopPayment money(int amount) {
        return new ShopPayment(0, List.of(CurrencyAmount.money(amount)));
    }

    public int totalAmount() {
        int total = 0;
        for (CurrencyAmount cost : this.costs) {
            total += cost.amount();
        }
        return total;
    }

    public @NotNull NbtList toNbtList() {
        NbtList list = new NbtList();
        for (CurrencyAmount cost : this.costs) {
            NbtCompound tag = new NbtCompound();
            tag.putString("currency", cost.currency().toString());
            tag.putInt("amount", cost.amount());
            list.add(tag);
        }
        return list;
    }
}
