package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.blackout.BlackoutEffectResult;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

/**
 * 记录 Wathe 停电系统当前“持有”的玩家药水效果。
 *
 * <p>停电会持续刷新短时间夜视/失明。这个组件只记 Wathe 自己上一次刷出的效果，
 * 结束停电、恢复供电或玩家重置时只尝试清理这份短效果，避免误删职业技能、
 * 药水物品或其它扩展模组主动给予的同名长效果。</p>
 */
public class PlayerBlackoutEffectComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<PlayerBlackoutEffectComponent> KEY =
            ComponentRegistry.getOrCreate(Wathe.id("blackout_effect"), PlayerBlackoutEffectComponent.class);

    private static final String OWNED_EFFECT_KEY = "OwnedEffect";
    private static final String OWNED_DURATION_KEY = "OwnedDuration";
    private static final int REFRESH_DURATION_TICKS = 60;
    private static final int OWNED_EFFECT_MAX_CLEAR_DURATION_TICKS = REFRESH_DURATION_TICKS + 10;

    private final PlayerEntity player;
    private BlackoutEffectResult.Action ownedEffect = BlackoutEffectResult.Action.NONE;
    private int ownedDuration = 0;

    public PlayerBlackoutEffectComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient) {
            KEY.sync(this.player);
        }
    }

    @Override
    public void serverTick() {
        if (this.ownedEffect == BlackoutEffectResult.Action.NONE || this.ownedDuration <= 0) {
            return;
        }

        this.ownedDuration--;
        if (this.ownedDuration <= 0) {
            /*
             * ownedDuration 是 Wathe 对“这份短时停电药水仍可安全视作自己发放”的保守窗口。
             * 停电仍在进行时，WorldBlackoutComponent 会每 tick 重新把它刷新到 60 tick；
             * 自然停电结束后，这里等最后一份短效果自己走完，再丢掉归属，避免以后误删职业、
             * 物品或其它扩展模组补上的同名药水。
             */
            this.clearOwnedStateOnly();
        }
    }

    /**
     * 按最终解析结果刷新停电药水效果。
     */
    public void applyResolvedEffect(@NotNull BlackoutEffectResult result) {
        switch (result.action()) {
            case NIGHT_VISION -> this.applyOwnedEffect(BlackoutEffectResult.Action.NIGHT_VISION);
            case BLINDNESS -> this.applyBlindnessUnlessNightVisionPresent();
            case NONE, PASS -> this.clearOwnedEffect();
        }
    }

    /**
     * 如果玩家已经有夜视，则失明立即解除且本 tick 不再补失明。
     *
     * <p>这里不区分夜视来源：无论夜视来自停电本身、职业技能还是药水，
     * 玩家一旦处于夜视状态，停电黑幕与停电失明都让位。</p>
     */
    private void applyBlindnessUnlessNightVisionPresent() {
        if (this.ownedEffect == BlackoutEffectResult.Action.NIGHT_VISION) {
            this.clearOwnedEffect();
        }

        if (this.player.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
            if (this.ownedEffect == BlackoutEffectResult.Action.BLINDNESS) {
                this.clearOwnedEffect();
            } else {
                this.clearOwnedStateOnly();
            }
            return;
        }
        this.applyOwnedEffect(BlackoutEffectResult.Action.BLINDNESS);
    }

    private void applyOwnedEffect(@NotNull BlackoutEffectResult.Action effect) {
        RegistryEntry<StatusEffect> target = getStatusEffect(effect);
        if (target == null) {
            this.clearOwnedEffect();
            return;
        }

        if (this.ownedEffect != effect) {
            this.clearOwnedEffect();
        }

        /*
         * 停电效果每 tick 刷新 3 秒短效果：
         * 1. 足够覆盖少量同步/卡顿；
         * 2. 即使回合被强行停止或组件未及时 tick，效果也会很快自然过期；
         * 3. 组件仍会在 reset / restore 时主动清理，避免跨局残留。
         */
        this.player.addStatusEffect(new StatusEffectInstance(target, REFRESH_DURATION_TICKS, 0, true, false, false));
        this.ownedEffect = effect;
        this.ownedDuration = REFRESH_DURATION_TICKS;
        this.sync();
    }

    /**
     * 清理 Wathe 停电系统拥有的短时效果。
     */
    public void clearOwnedEffect() {
        RegistryEntry<StatusEffect> target = getStatusEffect(this.ownedEffect);
        if (target != null && this.shouldRemoveCurrentEffect(target)) {
            this.player.removeStatusEffect(target);
        }
        this.clearOwnedStateOnly();
    }

    /**
     * 自然停电结束时释放 Wathe 对短时药水的持续刷新，让原版药水倒计时自己结束。
     *
     * <p>直接 removeStatusEffect 会让失明/夜视画面瞬间跳回正常，观感很突兀；
     * 原版药水自然倒计时结束时会保留客户端自己的淡出/恢复过渡。因此自然结束只停止续杯，
     * 不主动删除当前身上的短效果。</p>
     *
     * <p>这里仍暂时保留 ownedEffect 和 ownedDuration：如果玩家在这 3 秒残留期内进入
     * 新局、被 reset 或管理员执行 restore，强制清理路径仍能把 Wathe 残留药水拿掉。
     * ownedDuration 走完后会自动清掉归属，避免很久以后误删其它来源给出的同名药水。</p>
     */
    public void releaseOwnedEffectToExpireNaturally() {
        RegistryEntry<StatusEffect> target = getStatusEffect(this.ownedEffect);
        if (target == null) {
            this.clearOwnedStateOnly();
            return;
        }

        StatusEffectInstance current = this.player.getStatusEffect(target);
        if (current == null || current.getAmplifier() != 0 || current.getDuration() > OWNED_EFFECT_MAX_CLEAR_DURATION_TICKS) {
            /*
             * 如果当前没有同名效果，或者已经明显不是 Wathe 每 tick 刷新的 60 tick 短效果，
             * 就只放弃归属，不碰真实药水，避免误删扩展职业/药水物品给出的长时间效果。
             */
            this.clearOwnedStateOnly();
            return;
        }

        this.ownedDuration = Math.max(1, current.getDuration());
        this.sync();
    }

    /**
     * 只清理归属状态，不碰玩家身上的真实药水。
     *
     * <p>当玩家身上已有其它来源夜视时，停电失明会在这里放弃归属；
     * 这样不会把别人给的夜视或更长失明误判成 Wathe 自己的短效果。</p>
     */
    private void clearOwnedStateOnly() {
        if (this.ownedEffect == BlackoutEffectResult.Action.NONE && this.ownedDuration <= 0) {
            return;
        }
        this.ownedEffect = BlackoutEffectResult.Action.NONE;
        this.ownedDuration = 0;
        this.sync();
    }

    private boolean shouldRemoveCurrentEffect(@NotNull RegistryEntry<StatusEffect> target) {
        StatusEffectInstance current = this.player.getStatusEffect(target);
        return current != null
                && current.getAmplifier() == 0
                && current.getDuration() <= OWNED_EFFECT_MAX_CLEAR_DURATION_TICKS;
    }

    private static RegistryEntry<StatusEffect> getStatusEffect(@NotNull BlackoutEffectResult.Action effect) {
        return switch (effect) {
            case NIGHT_VISION -> StatusEffects.NIGHT_VISION;
            case BLINDNESS -> StatusEffects.BLINDNESS;
            case PASS, NONE -> null;
        };
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putString(OWNED_EFFECT_KEY, this.ownedEffect.name());
        tag.putInt(OWNED_DURATION_KEY, this.ownedDuration);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        if (tag.contains(OWNED_EFFECT_KEY)) {
            try {
                this.ownedEffect = BlackoutEffectResult.Action.valueOf(tag.getString(OWNED_EFFECT_KEY));
            } catch (IllegalArgumentException ignored) {
                this.ownedEffect = BlackoutEffectResult.Action.NONE;
            }
        } else {
            this.ownedEffect = BlackoutEffectResult.Action.NONE;
        }
        this.ownedDuration = tag.contains(OWNED_DURATION_KEY) ? Math.max(0, tag.getInt(OWNED_DURATION_KEY)) : 0;
    }
}
