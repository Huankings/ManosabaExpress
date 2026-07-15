package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.shop.ShopApi;
import dev.doctor4t.wathe.api.shop.ShopPurchaseContext;
import dev.doctor4t.wathe.api.shop.ShopPurchaseResult;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.record.ShopPurchaseTracker;
import dev.doctor4t.wathe.util.ShopEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

public class PlayerShopComponent implements AutoSyncedComponent, ServerTickingComponent, ClientTickingComponent {
    public static final ComponentKey<PlayerShopComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("shop"), PlayerShopComponent.class);
    private final PlayerEntity player;
    public int balance = 0;

    public PlayerShopComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        KEY.sync(this.player);
    }

    public void reset() {
        this.balance = 0;
        this.sync();
    }

    public void addToBalance(int amount) {
        this.setBalance(this.balance + amount);
    }

    public void setBalance(int amount) {
        this.balance = amount;
        this.sync();
    }

    public void tryBuy(int index) {
        ShopApi.ResolvedShop shop = ShopApi.resolveShop(this.player);
        if (index < 0 || index >= shop.entries().size()) return;

        ShopEntry entry = shop.entries().get(index);
        if (FabricLoader.getInstance().isDevelopmentEnvironment() && this.balance < entry.price())
            this.balance = entry.price() * 10;

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
            ShopPurchaseTracker.captureSuccessfulPurchase(this.player, entry, index, entry.price());
            this.balance -= entry.price();
            ShopApi.playBuySound(this.player);
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
        return WorldBlackoutComponent.KEY.get(player.getWorld()).triggerBlackout();
    }

    public static boolean usePsychoMode(@NotNull PlayerEntity player) {
        player.getItemCooldownManager().set(WatheItems.PSYCHO_MODE, GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.PSYCHO_MODE, 0));
        return PlayerPsychoComponent.KEY.get(player).startPsycho();
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putInt("Balance", this.balance);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.balance = tag.getInt("Balance");
    }
}
