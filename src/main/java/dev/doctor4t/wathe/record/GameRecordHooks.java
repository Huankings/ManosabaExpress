package dev.doctor4t.wathe.record;

import dev.doctor4t.wathe.api.event.GameEvents;
import net.minecraft.server.world.ServerWorld;

/**
 * 将本体生命周期事件接到回放记录系统上。
 */
public final class GameRecordHooks {
    private GameRecordHooks() {
    }

    public static void register() {
        GameEvents.ON_FINISH_FINALIZE.register((world, gameComponent) -> {
            if (world instanceof ServerWorld serverWorld) {
                GameRecordManager.endMatch(serverWorld);
            }
        });
    }
}
