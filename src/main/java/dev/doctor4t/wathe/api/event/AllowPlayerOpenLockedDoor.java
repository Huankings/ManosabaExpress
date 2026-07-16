package dev.doctor4t.wathe.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.entity.player.PlayerEntity;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

public interface AllowPlayerOpenLockedDoor {

    /**
     * 回调函数，用于判断玩家是否能开锁着的门。
     */
    Event<AllowPlayerOpenLockedDoor> EVENT = createArrayBacked(AllowPlayerOpenLockedDoor.class, listeners -> player -> {
        for (AllowPlayerOpenLockedDoor listener : listeners) {
            if (listener.allowOpen(player)) {
                return true;
            }
        }
        return false;
    });

    boolean allowOpen(PlayerEntity player);
}
