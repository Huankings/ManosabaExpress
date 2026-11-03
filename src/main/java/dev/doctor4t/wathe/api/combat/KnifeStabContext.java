package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端收到一次合法匕首刺击包后的公开上下文。
 *
 * <p>Wathe 在创建本上下文前已经权威校验攻击者仍按玩法存活、主手确实是默认匕首、
 * 匕首没有处于冷却、目标实体存在、距离不超过三格且目标允许 ATTACK。
 * 扩展处理非玩家实体时仍应检查自己的实体运行状态，例如播放体是否仍有效。</p>
 */
public final class KnifeStabContext {
    private final ServerPlayerEntity attacker;
    private final ItemStack stack;
    private final Entity target;
    private final GameWorldComponent game;

    public KnifeStabContext(
            @NotNull ServerPlayerEntity attacker,
            @NotNull ItemStack stack,
            @NotNull Entity target,
            @NotNull GameWorldComponent game
    ) {
        this.attacker = attacker;
        this.stack = stack;
        this.target = target;
        this.game = game;
    }

    public @NotNull ServerPlayerEntity attacker() {
        return this.attacker;
    }

    /** 返回收包时的主手快照；扩展只能读取，不能据此修改玩家真实物品。 */
    public @NotNull ItemStack stack() {
        return this.stack;
    }

    public @NotNull Entity target() {
        return this.target;
    }

    public @NotNull GameWorldComponent game() {
        return this.game;
    }

    /** 记录 Wathe 通用“匕首命中玩家”回放。非玩家实体应使用自己的回放格式。 */
    public void recordPlayerHit(@NotNull PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverTarget) {
            GameRecordManager.recordItemHit(
                    this.attacker,
                    this.stack,
                    GameConstants.DeathReasons.KNIFE,
                    serverTarget,
                    null
            );
        }
    }

    /** 复用 Wathe 默认死亡入口刺杀玩家。 */
    public void killPlayer(@NotNull PlayerEntity player) {
        GameFunctions.killPlayer(player, true, this.attacker, GameConstants.DeathReasons.KNIFE);
    }

    /** 播放默认刺中声音并同步主手挥动。 */
    public void playDefaultFeedback() {
        this.target.playSound(WatheSounds.ITEM_KNIFE_STAB, 1.0F, 1.0F);
        this.attacker.swingHand(Hand.MAIN_HAND, true);
    }

    public boolean defaultCooldownApplies() {
        return !this.attacker.isCreative() && this.game.getGameMode() != WatheGameModes.LOOSE_ENDS;
    }

    /**
     * 为非玩家扩展目标施加 Wathe 基础匕首冷却。
     *
     * <p>这里刻意不进入 {@link KnifeCooldownApi}：该流水线的上下文要求玩家目标，
     * 并可能依赖玩家死亡阶段产生的标记。扩展实体若要自定义冷却，应自行在 handler 中计算。</p>
     */
    public void applyBaseCooldown() {
        if (defaultCooldownApplies()) {
            this.attacker.getItemCooldownManager().set(
                    WatheItems.KNIFE,
                    GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.KNIFE, 0)
            );
        }
    }

    /**
     * 为默认玩家刺杀计算并写入完整冷却流水线。
     * 即使创造模式或 Loose Ends 不写冷却，也会执行 modifier 以消费一次性死亡标记。
     */
    public int applyModifiedCooldown(@NotNull PlayerEntity player) {
        int finalCooldown = KnifeCooldownApi.modifyCooldown(new KnifeCooldownContext(
                this.attacker,
                player,
                this.stack,
                this.game,
                GameConstants.ITEM_COOLDOWNS.getOrDefault(WatheItems.KNIFE, 0),
                defaultCooldownApplies()
        ));
        if (defaultCooldownApplies()) {
            this.attacker.getItemCooldownManager().set(WatheItems.KNIFE, finalCooldown);
        }
        return finalCooldown;
    }
}
