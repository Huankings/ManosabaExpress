package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.api.tray.TrayEffectHandler;
import dev.doctor4t.wathe.api.tray.TrayEffectRegistry;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 托盘扩展效果的通用工具。
 *
 * <p>这个工具类负责把托盘上的扩展效果：
 * 1. 从托盘方块实体转移到玩家拿出的物品；
 * 2. 从物品读取出来写进回放；
 * 3. 在玩家真正消费时触发对应处理器。</p>
 */
public final class TrayEffectUtils {
    private TrayEffectUtils() {
    }

    public static boolean hasTrayEffect(ItemStack stack) {
        return stack.contains(WatheDataComponentTypes.TRAY_EFFECT);
    }

    public static void attachTrayEffect(ItemStack stack, String effectId, @Nullable String effectOwner) {
        stack.set(WatheDataComponentTypes.TRAY_EFFECT, effectId);
        if (effectOwner != null) {
            stack.set(WatheDataComponentTypes.TRAY_EFFECT_OWNER, effectOwner);
        }
    }

    public static @Nullable Identifier getTrayEffectId(ItemStack stack) {
        String raw = stack.getOrDefault(WatheDataComponentTypes.TRAY_EFFECT, null);
        return raw == null ? null : Identifier.tryParse(raw);
    }

    public static @Nullable UUID getTrayEffectOwner(ItemStack stack) {
        String raw = stack.getOrDefault(WatheDataComponentTypes.TRAY_EFFECT_OWNER, null);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void appendReplayData(ItemStack stack, NbtCompound data) {
        Identifier effectId = getTrayEffectId(stack);
        if (effectId != null) {
            data.putString("tray_effect", effectId.toString());
        }
        UUID owner = getTrayEffectOwner(stack);
        if (owner != null) {
            data.putUuid("tray_effect_owner", owner);
        }
    }

    /**
     * 处理扩展托盘效果的消费逻辑。
     *
     * <p>一旦命中扩展效果，这里会先记 consume_item 回放，再回调扩展模组自己的真实效果实现。
     * 这样实时播报与赛后总回放都能继续复用 wathe 主体的时间线系统。</p>
     */
    public static boolean handleConsumeEffect(ServerPlayerEntity player, ItemStack consumedSnapshot, String consumeType) {
        Identifier effectId = getTrayEffectId(consumedSnapshot);
        if (effectId == null) {
            return false;
        }

        TrayEffectHandler handler = TrayEffectRegistry.getByEffectId(effectId);
        if (handler == null) {
            return false;
        }

        UUID owner = getTrayEffectOwner(consumedSnapshot);
        NbtCompound extra = new NbtCompound();
        appendReplayData(consumedSnapshot, extra);
        GameRecordManager.recordConsumeItem(player, consumedSnapshot, consumeType, false, null, extra);
        handler.onConsume(player, consumedSnapshot, consumeType, owner);
        return true;
    }
}
