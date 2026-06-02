package dev.doctor4t.wathe.client.util;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheItems;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class WatheItemTooltips {
    public static final int COOLDOWN_COLOR = 0xC90000;
    public static final int LETTER_COLOR = 0xC5AE8B;
    public static final int REGULAR_TOOLTIP_COLOR = 0x808080;

    public static void addTooltips() {
        ItemTooltipCallback.EVENT.register((itemStack, tooltipContext, tooltipType, tooltipList) -> {
            addCooldownText(WatheItems.KNIFE, tooltipList, itemStack);
            addCooldownText(WatheItems.REVOLVER, tooltipList, itemStack);
            addCooldownText(WatheItems.DERRINGER, tooltipList, itemStack);
            addCooldownText(WatheItems.GRENADE, tooltipList, itemStack);
            addCooldownText(WatheItems.LOCKPICK, tooltipList, itemStack);
            addCooldownText(WatheItems.CROWBAR, tooltipList, itemStack);
            addCooldownText(WatheItems.BODY_BAG, tooltipList, itemStack);
            addCooldownText(WatheItems.PSYCHO_MODE, tooltipList, itemStack);
            addCooldownText(WatheItems.BLACKOUT, tooltipList, itemStack);

            addTooltipForItem(WatheItems.KNIFE, itemStack, tooltipList);
            addTooltipForItem(WatheItems.REVOLVER, itemStack, tooltipList);
            addTooltipForItem(WatheItems.DERRINGER, itemStack, tooltipList);
            addTooltipForItem(WatheItems.GRENADE, itemStack, tooltipList);
            addTooltipForItem(WatheItems.PSYCHO_MODE, itemStack, tooltipList);
            addTooltipForItem(WatheItems.POISON_VIAL, itemStack, tooltipList);
            addTooltipForItem(WatheItems.SCORPION, itemStack, tooltipList);
            addTooltipForItem(WatheItems.FIRECRACKER, itemStack, tooltipList);
            addTooltipForItem(WatheItems.LOCKPICK, itemStack, tooltipList);
            addTooltipForItem(WatheItems.CROWBAR, itemStack, tooltipList);
            addTooltipForItem(WatheItems.BODY_BAG, itemStack, tooltipList);
            addTooltipForItem(WatheItems.BLACKOUT, itemStack, tooltipList);
            addTooltipForItem(WatheItems.NOTE, itemStack, tooltipList);
        });
    }

    private static void addTooltipForItem(Item item, @NotNull ItemStack itemStack, List<Text> tooltipList) {
        if (itemStack.isOf(item)) {
            tooltipList.addAll(TextUtils.getTooltipForItem(item, Style.EMPTY.withColor(REGULAR_TOOLTIP_COLOR)));
        }
    }

    private static void addCooldownText(Item item, List<Text> tooltipList, @NotNull ItemStack itemStack) {
        if (!itemStack.isOf(item)) return;
        if (MinecraftClient.getInstance().player == null) return;
        ItemCooldownManager itemCooldownManager = MinecraftClient.getInstance().player.getItemCooldownManager();
        if (itemCooldownManager.isCoolingDown(item)) {
            ItemCooldownManager.Entry knifeEntry = itemCooldownManager.entries.get(item);
            int timeLeft = knifeEntry.endTick - itemCooldownManager.tick;
            if (timeLeft > 0) {
                /*
                 * 左轮的总冷却时长现在取决于当前玩家所属阵营，
                 * 因此这里优先用“阵营动态冷却”去换算显示给玩家的剩余时间。
                 */
                int totalCooldown = item == WatheItems.REVOLVER
                        ? GameConstants.getRevolverCooldown(MinecraftClient.getInstance().player)
                        : timeLeft;
                float progress = itemCooldownManager.getCooldownProgress(item, 0);
                int displayTicks = item == WatheItems.REVOLVER
                        ? Math.max(timeLeft, (int) (totalCooldown * progress) + 19)
                        : timeLeft;
                int minutes = (int) Math.floor((double) displayTicks / 1200);
                int seconds = (displayTicks - (minutes * 1200)) / 20;
                String countdown = (minutes > 0 ? minutes + "m" : "") + (seconds > 0 ? seconds + "s" : "");
                tooltipList.add(Text.translatable("tip.cooldown", countdown).withColor(COOLDOWN_COLOR));
            }
        }
    }
}
