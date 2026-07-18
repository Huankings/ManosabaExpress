package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.shop.ShopPrice;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

public class ShopEntry {
    private final ItemStack stack;
    private final ShopPrice price;
    private final Type type;

    @FunctionalInterface
    public interface PurchaseAction {
        boolean buy(@NotNull PlayerEntity player);
    }

    public enum Type {
        WEAPON("gui/shop_slot_weapon"),
        POISON("gui/shop_slot_poison"),
        TOOL("gui/shop_slot_tool");

        final Identifier texture;

        Type(String texture) {
            this.texture = Wathe.id(texture);
        }

        public Identifier getTexture() {
            return texture;
        }
    }

    public ShopEntry(ItemStack stack, int price, Type type) {
        this(stack, ShopPrice.money(price), type);
    }

    public ShopEntry(@NotNull ItemStack stack, @NotNull ShopPrice price, @NotNull Type type) {
        this.stack = stack;
        this.price = price;
        this.type = type;
    }

    public boolean onBuy(@NotNull PlayerEntity player) {
        if (GameWorldComponent.KEY.get(player.getWorld()).canUseKillerFeatures(player)) {
            return insertStackInFreeSlot(player, this.stack.copy());
        } else return false;
    }

    /**
     * 是否由 Wathe 显示通用“购买失败”提示。
     *
     * <p>普通商品失败时应该提示；但某些即时商品会自己给出更具体的原因，
     * 例如“当前没有停电”或“刺刀不在冷却中”。这类条目可以覆写为 false，
     * 让 Wathe 只播放失败音效，不覆盖特殊提示。</p>
     */
    public boolean shouldShowPurchaseFailedMessage(@NotNull PlayerEntity player) {
        return true;
    }

    /**
     * 创建一个绕过杀手能力限制、直接放入快捷栏空位的商品。
     *
     * <p>Wathe 原版默认商品只允许杀手能力角色购买；非杀手职业如果也有商店，
     * 应使用这个入口或 {@link #giveToInventory(ItemStack, int, Type)}，避免再为发物品写 mixin。</p>
     */
    public static @NotNull ShopEntry directToHotbar(@NotNull ItemStack stack, int price, @NotNull Type type) {
        return directToHotbar(stack, ShopPrice.money(price), type);
    }

    public static @NotNull ShopEntry directToHotbar(@NotNull ItemStack stack, @NotNull ShopPrice price, @NotNull Type type) {
        return new ShopEntry(stack, price, type) {
            @Override
            public boolean onBuy(@NotNull PlayerEntity player) {
                return insertStackInFreeSlot(player, stack().copy());
            }
        };
    }

    /**
     * 创建一个绕过杀手能力限制、交给玩家背包自动接收的商品。
     *
     * <p>这个入口更接近部分旧扩展商店的行为：只要背包能接收，就允许购买；
     * 不再强制只能放进前 9 格快捷栏。</p>
     */
    public static @NotNull ShopEntry giveToInventory(@NotNull ItemStack stack, int price, @NotNull Type type) {
        return giveToInventory(stack, ShopPrice.money(price), type);
    }

    public static @NotNull ShopEntry giveToInventory(@NotNull ItemStack stack, @NotNull ShopPrice price, @NotNull Type type) {
        return new ShopEntry(stack, price, type) {
            @Override
            public boolean onBuy(@NotNull PlayerEntity player) {
                return player.giveItemStack(stack().copy());
            }
        };
    }

    /**
     * 创建一个“购买即执行动作”的商品。
     *
     * <p>例如恢复停电、刷新冷却、启动疯魔模式这类商品并不会真的进入背包，
     * 而是在购买成功的瞬间执行逻辑。显示用的 {@code stack} 仍会用于商店图标和回放记录。</p>
     */
    public static @NotNull ShopEntry action(@NotNull ItemStack stack, int price, @NotNull Type type, @NotNull PurchaseAction action) {
        return action(stack, price, type, action, true);
    }

    public static @NotNull ShopEntry action(@NotNull ItemStack stack, @NotNull ShopPrice price, @NotNull Type type, @NotNull PurchaseAction action) {
        return action(stack, price, type, action, true);
    }

    /**
     * 创建一个“购买即执行动作”的商品，并控制失败时是否显示通用失败提示。
     */
    public static @NotNull ShopEntry action(
            @NotNull ItemStack stack,
            int price,
            @NotNull Type type,
            @NotNull PurchaseAction action,
            boolean showPurchaseFailedMessage
    ) {
        return action(stack, ShopPrice.money(price), type, action, showPurchaseFailedMessage);
    }

    /**
     * 创建一个“购买即执行动作”的商品，并支持多货币价格。
     */
    public static @NotNull ShopEntry action(
            @NotNull ItemStack stack,
            @NotNull ShopPrice price,
            @NotNull Type type,
            @NotNull PurchaseAction action,
            boolean showPurchaseFailedMessage
    ) {
        return new ShopEntry(stack, price, type) {
            @Override
            public boolean onBuy(@NotNull PlayerEntity player) {
                return action.buy(player);
            }

            @Override
            public boolean shouldShowPurchaseFailedMessage(@NotNull PlayerEntity player) {
                return showPurchaseFailedMessage;
            }
        };
    }

    public static boolean insertStackInFreeSlot(@NotNull PlayerEntity player, ItemStack stackToInsert) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                player.getInventory().setStack(i, stackToInsert);
                return true;
            }
        }
        return false;
    }

    public ItemStack stack() {
        return this.stack;
    }

    public int price() {
        return this.price.legacyPrice();
    }

    public @NotNull ShopPrice shopPrice() {
        return this.price;
    }

    public Type type() {
        return type;
    }
}
