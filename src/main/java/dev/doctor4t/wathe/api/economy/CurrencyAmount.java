package dev.doctor4t.wathe.api.economy;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 一段具体的货币数量。
 *
 * <p>商店价格、实际扣款、回放记录都会复用这个结构。这样“金币”“任务币”
 * 以及后续扩展模组注册的自定义材料，都能用同一套字段表达。</p>
 */
public record CurrencyAmount(@NotNull Identifier currency, int amount) {
    public CurrencyAmount {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("Currency amount cannot be negative");
        }
    }

    public static @NotNull CurrencyAmount of(@NotNull Identifier currency, int amount) {
        return new CurrencyAmount(currency, amount);
    }

    public static @NotNull CurrencyAmount money(int amount) {
        return of(EconomyApi.MONEY, amount);
    }

    public static @NotNull CurrencyAmount taskMoney(int amount) {
        return of(EconomyApi.TASK_MONEY, amount);
    }
}
