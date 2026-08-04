package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.blackout.BlackoutApi;
import dev.doctor4t.wathe.api.economy.CurrencyAmount;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.api.shop.ShopApi;
import dev.doctor4t.wathe.api.shop.ShopPayment;
import dev.doctor4t.wathe.api.shop.ShopPurchaseContext;
import dev.doctor4t.wathe.api.shop.ShopPurchaseResult;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.record.ShopPurchaseTracker;
import dev.doctor4t.wathe.util.ShopEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.HashMap;
import java.util.Map;

public class PlayerShopComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<PlayerShopComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("shop"), PlayerShopComponent.class);
    private final PlayerEntity player;
    /**
     * 旧版金币字段保留为 public，避免已经编译或正在源码引用 {@code component.balance}
     * 的扩展模组立刻失效。新的通用经济逻辑会把它视为 {@link EconomyApi#MONEY} 的镜像字段。
     */
    public int balance = 0;
    private final Map<Identifier, Integer> currencyBalances = new HashMap<>();

    public PlayerShopComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        KEY.sync(this.player);
    }

    public void reset() {
        this.balance = 0;
        this.currencyBalances.clear();
        this.sync();
    }

    public void addToBalance(int amount) {
        this.setBalance(this.balance + amount);
    }

    public void setBalance(int amount) {
        this.balance = amount;
        this.sync();
    }

    public int getCurrencyAmount(@NotNull Identifier currency) {
        if (currency.equals(EconomyApi.MONEY)) {
            return this.balance;
        }
        return this.currencyBalances.getOrDefault(currency, 0);
    }

    public void setCurrencyAmount(@NotNull Identifier currency, int amount) {
        this.setCurrencyAmountUnsynced(currency, amount);
        this.sync();
    }

    public void addCurrencyAmount(@NotNull Identifier currency, int amount) {
        this.setCurrencyAmount(currency, this.getCurrencyAmount(currency) + amount);
    }

    public @NotNull Map<Identifier, Integer> getCurrencyBalancesSnapshot() {
        Map<Identifier, Integer> snapshot = new HashMap<>(this.currencyBalances);
        snapshot.put(EconomyApi.MONEY, this.balance);
        snapshot.entrySet().removeIf(entry -> entry.getValue() <= 0);
        return Map.copyOf(snapshot);
    }

    private void setCurrencyAmountUnsynced(@NotNull Identifier currency, int amount) {
        int clamped = Math.max(0, amount);
        if (currency.equals(EconomyApi.MONEY)) {
            this.balance = clamped;
            return;
        }

        if (clamped <= 0) {
            this.currencyBalances.remove(currency);
        } else {
            this.currencyBalances.put(currency, clamped);
        }
    }

    private void addDevelopmentFunds(@NotNull ShopPayment payment) {
        for (CurrencyAmount cost : payment.costs()) {
            /*
             * 开发环境旧逻辑会在余额不足时自动补金币，方便点商店测试。
             * 多货币后同样补齐当前选中的测试方案，避免任务币商品在 dev 环境无法直接试买。
             */
            this.setCurrencyAmountUnsynced(cost.currency(), Math.max(this.getCurrencyAmount(cost.currency()), cost.amount() * 10));
        }
    }

    private boolean spend(@NotNull ShopPayment payment) {
        for (CurrencyAmount cost : payment.costs()) {
            if (this.getCurrencyAmount(cost.currency()) < cost.amount()) {
                return false;
            }
        }

        for (CurrencyAmount cost : payment.costs()) {
            this.setCurrencyAmountUnsynced(cost.currency(), this.getCurrencyAmount(cost.currency()) - cost.amount());
        }
        return true;
    }

    public void tryBuy(int index) {
        ShopApi.ResolvedShop shop = ShopApi.resolveShop(this.player);
        if (index < 0 || index >= shop.entries().size()) return;

        ShopEntry entry = shop.entries().get(index);
        ShopPayment payment = entry.shopPrice().selectPayment(this);
        if (payment == null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ShopPayment developmentPayment = entry.shopPrice().cheapestPaymentForDevelopment();
            if (developmentPayment != null) {
                this.addDevelopmentFunds(developmentPayment);
                payment = entry.shopPrice().selectPayment(this);
            }
        }

        if (payment == null) {
            if (entry.shouldShowPurchaseFailedMessage(this.player)) {
                ShopApi.sendPurchaseFailedMessage(this.player);
            }
            ShopApi.playFailSound(this.player);
            this.sync();
            return;
        }

        ShopPurchaseContext context = new ShopPurchaseContext(
                this.player,
                this,
                entry,
                index,
                shop.gameWorld(),
                shop.role(),
                shop.roleSpecificShop()
        );
        ShopPurchaseResult result = shop.provider().purchase(context);
        if (result == null) {
            result = ShopPurchaseResult.FAIL_SHOW_MESSAGE;
        }

        if (result.successful()) {
            /*
             * 不论商品来自 Wathe 原版列表，还是来自扩展职业注册的动态商店，
             * 成功后都在这里统一回填真实商品。StoreBuyPayload 记录回放时会优先消费这条记录，
             * 避免“客户端看见扩展商品，回放却按原版格子号显示匕首/左轮”的错位。
             */
            if (this.spend(payment)) {
                ShopPurchaseTracker.captureSuccessfulPurchase(this.player, entry, index, payment);
                ShopApi.playBuySound(this.player);
            } else {
                if (entry.shouldShowPurchaseFailedMessage(this.player)) {
                    ShopApi.sendPurchaseFailedMessage(this.player);
                }
                ShopApi.playFailSound(this.player);
            }
        } else {
            if (result.shouldNotifyFailure() && entry.shouldShowPurchaseFailedMessage(this.player)) {
                ShopApi.sendPurchaseFailedMessage(this.player);
            }
            ShopApi.playFailSound(this.player);
        }
        this.sync();
    }

    @Override
    public void clientTick() {

    }

    @Override
    public void serverTick() {

    }

    public static boolean useBlackout(@NotNull PlayerEntity player) {
        player.getItemCooldownManager().set(WatheItems.BLACKOUT, GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.BLACKOUT, 0));
        return player.getWorld() instanceof ServerWorld serverWorld && BlackoutApi.trigger(serverWorld);
    }

    public static boolean usePsychoMode(@NotNull PlayerEntity player) {
        player.getItemCooldownManager().set(WatheItems.PSYCHO_MODE, GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.PSYCHO_MODE, 0));
        return PlayerPsychoComponent.KEY.get(player).startPsycho();
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putInt("Balance", this.balance);

        NbtCompound currencies = new NbtCompound();
        for (Map.Entry<Identifier, Integer> entry : this.currencyBalances.entrySet()) {
            if (entry.getValue() > 0) {
                currencies.putInt(entry.getKey().toString(), entry.getValue());
            }
        }
        tag.put("CurrencyBalances", currencies);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.balance = tag.getInt("Balance");
        this.currencyBalances.clear();

        if (tag.contains("CurrencyBalances", NbtElement.COMPOUND_TYPE)) {
            NbtCompound currencies = tag.getCompound("CurrencyBalances");
            for (String key : currencies.getKeys()) {
                Identifier currency = Identifier.tryParse(key);
                if (currency != null && !currency.equals(EconomyApi.MONEY)) {
                    int amount = currencies.getInt(key);
                    if (amount > 0) {
                        this.currencyBalances.put(currency, amount);
                    }
                }
            }
        }
    }
}
