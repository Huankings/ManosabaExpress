package dev.doctor4t.wathe.record;

import net.minecraft.nbt.NbtCompound;

import java.util.UUID;

/**
 * 单条对局记录事件。
 *
 * <p>这里不直接序列化成固定 Java 字段结构，而是保留一个 NBT data，
 * 这样后续扩展职业模组可以自由追加字段，不需要频繁改主模组基类。</p>
 */
public record GameRecordEvent(
        UUID matchId,
        int seq,
        String type,
        long worldTick,
        long realTimeMs,
        NbtCompound data
) {
}
