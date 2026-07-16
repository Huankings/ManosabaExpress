package dev.doctor4t.wathe.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.entity.player.PlayerEntity;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

public interface CanSeePoison {

    /**
     * 用于判断玩家是否能看到饮料盘上的毒素颗粒的回调。
     */
    Event<CanSeePoison> EVENT = createArrayBacked(CanSeePoison.class, listeners -> player -> {
        for (CanSeePoison listener : listeners) {
            if (listener.visible(player)) {
                return true;
            }
        }
        return false;
    });

    boolean visible(PlayerEntity player);
}
