package dev.doctor4t.wathe.task;

import dev.doctor4t.wathe.api.event.GameEvents;
import dev.doctor4t.wathe.cca.TaskPointWorldComponent;
import dev.doctor4t.wathe.util.TaskPointSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

/**
 * 任务点同步管理器。
 *
 * <p>职责分成三件事：
 * 1. 手动或自动触发服务端重新扫描任务点；
 * 2. 把当前缓存同步给某个玩家或整个世界的所有玩家；
 * 3. 监听“玩家进服 / 每局开始”这些固定时机，自动做同步或重载。
 */
public final class TaskPointSyncManager {

    private TaskPointSyncManager() {
    }

    public static void initialize() {
        /**
         * 新玩家进服时，把当前已经记录好的任务点表直接发给他，
         * 这样不需要等管理员手动执行刷新指令。
         */
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.player));

        /**
         * 每局初始化完成后，如果开关是开着的，就自动重扫一遍。
         * 如果关掉自动重扫，则至少把当前缓存重新广播一次，保证本局玩家都拿到最新客户端数据。
         */
        GameEvents.ON_FINISH_INITIALIZE.register((world, gameComponent) -> {
            if (world instanceof ServerWorld serverWorld) {
                TaskPointWorldComponent component = TaskPointWorldComponent.KEY.get(serverWorld);
                if (component.isAutoRefreshOnGameStart()) {
                    reloadAndBroadcast(serverWorld);
                } else {
                    broadcast(serverWorld);
                }
            }
        });
    }

    /**
     * 重新扫描当前世界任务点，并把新结果广播给所有在线玩家。
     */
    public static void reloadAndBroadcast(@NotNull ServerWorld world) {
        TaskPointWorldComponent component = TaskPointWorldComponent.KEY.get(world);
        component.setTaskPoints(TaskPointScanner.scan(world));
        broadcast(world);
    }

    /**
     * 不重新扫描，只把当前缓存重新广播。
     *
     * <p>这个方法主要服务于“客户端没同步到，但服务端记录其实没问题”的场景。
     */
    public static void broadcast(@NotNull ServerWorld world) {
        TaskPointSyncPayload payload = new TaskPointSyncPayload(TaskPointWorldComponent.KEY.get(world).createSnapshot());
        for (ServerPlayerEntity player : world.getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * 单独给一个玩家发送当前缓存。
     */
    public static void sendTo(@NotNull ServerPlayerEntity player) {
        World world = player.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        TaskPointSyncPayload payload = new TaskPointSyncPayload(TaskPointWorldComponent.KEY.get(serverWorld).createSnapshot());
        ServerPlayNetworking.send(player, payload);
    }
}
