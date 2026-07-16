package dev.doctor4t.wathe.api.event;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

public interface AllowPlayerDeath {

    /**
     * 事件回调，用于确定玩家是否允许因特定的死亡类型而死亡
     * 游戏当前已定义以下死亡类型名称：
     * 'fell_out_of_train'（从火车上摔下）、'poison'（中毒）、'grenade'（爆炸）、'bat_hit'（被球棒击打）、'gun_shot'（枪击）、'knife_stab'（被刀刺）。
     * 任何未明确定义的其他死亡类型将默认为 'generic'（通用）。
     * @see dev.doctor4t.wathe.game.GameConstants.DeathReasons
     */
    Event<AllowPlayerDeath> EVENT = createArrayBacked(AllowPlayerDeath.class, listeners -> (victim, killer, deathReason) -> {
        for (AllowPlayerDeath listener : listeners) {
            if (!listener.allowDeath(victim, killer, deathReason)) {
                return false;
            }
        }
        return true;
    });

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean allowDeath(PlayerEntity victim, PlayerEntity killer, Identifier deathReason);
}