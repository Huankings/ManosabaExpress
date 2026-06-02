package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.client.task.TaskPointClientState;
import dev.doctor4t.wathe.task.TaskPointType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端 -> 客户端的任务点同步包。
 *
 * <p>这里同步的是“坐标 -> 多个任务点类型”的整张表。
 * 网络层使用位掩码压缩类型集合，减少包体积；
 * 客户端收到后再还原为 {@link EnumSet} 方便渲染过滤。
 */
public record TaskPointSyncPayload(Map<BlockPos, EnumSet<TaskPointType>> taskPoints) implements CustomPayload {
    public static final Id<TaskPointSyncPayload> ID = new Id<>(Wathe.id("task_point_sync"));
    public static final PacketCodec<PacketByteBuf, TaskPointSyncPayload> CODEC =
            PacketCodec.of(TaskPointSyncPayload::write, TaskPointSyncPayload::read);

    public TaskPointSyncPayload {
        taskPoints = copyTaskPoints(taskPoints);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private void write(@NotNull PacketByteBuf buf) {
        buf.writeVarInt(this.taskPoints.size());
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : this.taskPoints.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(TaskPointType.toBitMask(entry.getValue()));
        }
    }

    private static @NotNull TaskPointSyncPayload read(@NotNull PacketByteBuf buf) {
        int size = buf.readVarInt();
        HashMap<BlockPos, EnumSet<TaskPointType>> taskPoints = new HashMap<>();

        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            int bitMask = buf.readVarInt();
            taskPoints.put(pos, TaskPointType.fromBitMask(bitMask));
        }

        return new TaskPointSyncPayload(taskPoints);
    }

    private static @NotNull HashMap<BlockPos, EnumSet<TaskPointType>> copyTaskPoints(@NotNull Map<BlockPos, EnumSet<TaskPointType>> source) {
        HashMap<BlockPos, EnumSet<TaskPointType>> copy = new HashMap<>();
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : source.entrySet()) {
            copy.put(entry.getKey().toImmutable(), EnumSet.copyOf(entry.getValue()));
        }
        return copy;
    }

    @Environment(EnvType.CLIENT)
    public static class Receiver implements ClientPlayNetworking.PlayPayloadHandler<TaskPointSyncPayload> {
        @Override
        public void receive(@NotNull TaskPointSyncPayload payload, ClientPlayNetworking.@NotNull Context context) {
            TaskPointClientState.replaceTaskPoints(payload.taskPoints());
        }
    }
}
