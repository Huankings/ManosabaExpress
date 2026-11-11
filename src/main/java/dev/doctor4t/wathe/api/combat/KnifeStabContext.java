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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端收到一次合法匕首刺击包后的公开上下文。
 *
 * <p>Wathe 在创建本上下文前已经权威校验主手确实是默认匕首、目标实体存在、
 * 距离不超过三格且目标允许 ATTACK。玩法存活攻击者还会校验匕首冷却；普通非存活攻击者
 * 则保留 Wathe 旧版“可以刺杀存活目标且不受匕首 CD 限制”的调试/旁观语义。
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

    /**
     * 播放默认刺中声音。
     *
     * <p>匕首本身已经使用 SPEAR 蓄力姿势；松手完成刺杀后不再广播额外 swingHand，
     * 否则第一、第三人称都会在收刀后多播放一次普通挥手动画。</p>
     */
    public void playDefaultFeedback() {
        this.target.playSound(WatheSounds.ITEM_KNIFE_STAB, 1.0F, 1.0F);
    }

    public boolean defaultCooldownApplies() {
        /*
         * 非存活攻击者在 Wathe 旧行为中可以连续使用匕首，不能因为 API 公开化而开始写入 CD。
         * 特殊存活授权的 spectator/creative 会由 isPlayerAliveAndSurvival 返回 true；其中 creative
         * 仍沿用原有创造模式免冷却，spectator 授权状态则按正常玩法规则写入冷却。
         */
        return GameFunctions.isPlayerAliveAndSurvival(this.attacker)
                && !this.attacker.isCreative()
                && this.game.getGameMode() != WatheGameModes.LOOSE_ENDS;
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
