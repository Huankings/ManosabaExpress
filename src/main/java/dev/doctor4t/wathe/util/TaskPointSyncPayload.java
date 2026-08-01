package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.client.task.TaskPointClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 服务端 -> 客户端的任务点同步包。
 *
 * <p>这里同步的是“坐标 -> 多个任务点类型”的整张表。
 * 网络层直接同步任务点类型 id 列表，不再使用旧 enum bitmask。
 * 这样扩展 mod 注册多少个任务点类型都不会受 int 位数限制。
 */
public record TaskPointSyncPayload(Map<BlockPos, Set<Identifier>> taskPoints) implements CustomPayload {
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
        for (Map.Entry<BlockPos, Set<Identifier>> entry : this.taskPoints.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue().size());
            for (Identifier taskPointId : entry.getValue()) {
                buf.writeIdentifier(taskPointId);
            }
        }
    }

    private static @NotNull TaskPointSyncPayload read(@NotNull PacketByteBuf buf) {
        int size = buf.readVarInt();
        HashMap<BlockPos, Set<Identifier>> taskPoints = new HashMap<>();

        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            int typeCount = buf.readVarInt();
            java.util.LinkedHashSet<Identifier> types = new java.util.LinkedHashSet<>();
            for (int typeIndex = 0; typeIndex < typeCount; typeIndex++) {
                types.add(buf.readIdentifier());
            }
            taskPoints.put(pos, types);
        }

        return new TaskPointSyncPayload(taskPoints);
    }

    private static @NotNull HashMap<BlockPos, Set<Identifier>> copyTaskPoints(@NotNull Map<BlockPos, Set<Identifier>> source) {
        HashMap<BlockPos, Set<Identifier>> copy = new HashMap<>();
        for (Map.Entry<BlockPos, Set<Identifier>> entry : source.entrySet()) {
            copy.put(entry.getKey().toImmutable(), Set.copyOf(entry.getValue()));
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
