package dev.doctor4t.wathe.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.entity.player.PlayerEntity;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

public interface AllowPlayerPunching {

    /**
     * 用于判断玩家是否可以打另一个玩家的回调。
     */
    Event<AllowPlayerPunching> EVENT = createArrayBacked(AllowPlayerPunching.class, listeners -> (attacker, victim) -> {
        for (AllowPlayerPunching listener : listeners) {
            if (listener.allowPunching(attacker, victim)) {
                return true;
            }
        }
        return false;
    });

    boolean allowPunching(PlayerEntity attacker, PlayerEntity victim);
}
