package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 托盘附加效果注册表。
 *
 * <p>扩展职业模组可以在初始化时把自己的“试剂 / 药剂 / 特殊食物效果”注册进来，
 * wathe 的托盘系统会统一负责：
 * 1. 识别该物品能否放入托盘；
 * 2. 把效果元数据带到拿出的物品上；
 * 3. 在真实消费时回调对应处理器；
 * 4. 把回放需要的 effect id 保留下来，供扩展模组格式化文本。</p>
 */
public final class TrayEffectRegistry {
    private static final Map<Identifier, TrayEffectHandler> BY_EFFECT_ID = new HashMap<>();
    private static final Map<Item, TrayEffectHandler> BY_ADDITIVE_ITEM = new IdentityHashMap<>();

    private TrayEffectRegistry() {
    }

    public static void register(TrayEffectHandler handler) {
        BY_EFFECT_ID.put(handler.effectId(), handler);
        BY_ADDITIVE_ITEM.put(handler.additiveItem(), handler);
    }

    public static @Nullable TrayEffectHandler getByEffectId(Identifier effectId) {
        return BY_EFFECT_ID.get(effectId);
    }

    public static @Nullable TrayEffectHandler getByAdditiveItem(Item item) {
        return BY_ADDITIVE_ITEM.get(item);
    }

    /**
     * FoodPlatterBlock / DrinkTrayBlock 的统一入口。
     *
     * <p>如果主手拿着的是某个已注册的托盘效果物品，就交给对应处理器决定能否放置并执行逻辑。</p>
     */
    public static boolean tryApplyHeldEffect(ServerPlayerEntity player, BeveragePlateBlockEntity plate, BlockPos pos) {
        ItemStack heldStack = player.getMainHandStack();
        TrayEffectHandler handler = getByAdditiveItem(heldStack.getItem());
        if (handler == null || !handler.canApply(plate, player, heldStack)) {
            return false;
        }
        handler.applyToTray(plate, player, heldStack, pos);
        return true;
    }

    /**
     * 供扩展模组复用的“标准下托盘”实现。
     *
     * <p>默认会处理：
     * 1. 写入托盘效果与施加者；
     * 2. 按配置选择是否清除原生毒药 / 覆盖已有扩展效果；
     * 3. 消耗手中的试剂；
     * 4. 播放音效；
     * 5. 记录 item_use 回放事件。</p>
     */
    public static boolean applyStandardEffect(
            ServerPlayerEntity player,
            ItemStack heldStack,
            BeveragePlateBlockEntity plate,
            BlockPos pos,
            Identifier effectId,
            boolean clearPoison,
            boolean replaceExistingEffect
    ) {
        return applyStandardEffect(player, heldStack, plate, pos, effectId, clearPoison, replaceExistingEffect, null);
    }

    /**
     * 带自定义回放附加字段的标准下托盘实现。
     *
     * <p>扩展模组如果想让同一种物品在不同放置场景下显示不同回放文案，
     * 就可以通过 replayExtra 额外写入“placement / mode / custom flags”等字段，
     * 而无需复制整段标准托盘逻辑。</p>
     */
    public static boolean applyStandardEffect(
            ServerPlayerEntity player,
            ItemStack heldStack,
            BeveragePlateBlockEntity plate,
            BlockPos pos,
            Identifier effectId,
            boolean clearPoison,
            boolean replaceExistingEffect,
            @Nullable NbtCompound replayExtra
    ) {
        if (!clearPoison && plate.getPoisoner() != null) {
            return false;
        }
        if (!replaceExistingEffect && plate.getTrayEffect() != null) {
            return false;
        }

        if (clearPoison) {
            plate.setPoisoner(null);
        }

        /*
         * 先把“这次到底是哪个物品把什么效果下到了托盘里”缓存下来，
         * 再去真正消耗手里的物品。
         *
         * 这样做有两个目的：
         * 1. 避免某些 1.21.1 物品在 decrement(1) 后堆栈进入 empty 状态，
         *    导致后续回放拿到的 item id 不稳定；
         * 2. 给扩展模组留下 tray_effect / owner / item_name 这些额外字段，
         *    让回放可以优先按“托盘效果”而不是“物品本身”来格式化。
         */
        Identifier additiveItemId = Registries.ITEM.getId(heldStack.getItem());
        String additiveItemName = Text.Serialization.toJsonString(heldStack.getName(), player.getRegistryManager());

        plate.setTrayEffect(effectId.toString(), player.getUuidAsString());

        heldStack.decrement(1);
        player.playSoundToPlayer(SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.5f, 1f);

        NbtCompound extra = replayExtra == null ? new NbtCompound() : replayExtra.copy();
        extra.putString("tray_effect", effectId.toString());
        extra.putUuid("tray_effect_owner", player.getUuid());
        extra.putString("item_name", additiveItemName);
        GameRecordManager.putBlockPos(extra, "pos", pos);
        GameRecordManager.recordItemUse(player, additiveItemId, null, extra);
        return true;
    }
}
