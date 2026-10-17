package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.game.GameConstants;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

/**
 * 玩家体力组件。
 *
 * <p>旧版体力值直接存在 {@code PlayerEntityMixin} 的私有字段里，扩展模组想读写体力只能继续写 mixin。
 * 现在把“当前体力、额外体力上限修正、单局初始化标记”统一迁到 CCA 组件，公开 API 再基于这个组件读写。
 * 这样扩展职业可以直接清空、回满、增减体力或调整体力上限，而不需要碰玩家实体内部字段。</p>
 */
public class PlayerStaminaComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<PlayerStaminaComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("stamina"), PlayerStaminaComponent.class);

    private final PlayerEntity player;

    /**
     * 当前体力值。
     *
     * <p>保留 float 是为了兼容 Wathe 原本 0.8 / 0.6 / 0.4 这类非整数恢复速度。</p>
     */
    private float stamina = 0.0F;

    /**
     * 附加体力上限修正。
     *
     * <p>基础上限仍来自当前职业 {@link Role#getMaxSprintTime()}。
     * 扩展职业或词条如果要临时增加 / 减少体力上限，只改这里；最终上限 = 职业基础上限 + 该修正。
     * 基础上限为 -1 的无限体力职业仍视为无限体力，修正值不会把它变成有限体力。</p>
     */
    private float maxStaminaBonus = 0.0F;

    /**
     * 本局是否已经执行过“有限体力开局清零”。
     *
     * <p>玩家可能在同一局里临时切创造 / 旁观再切回来，不能因此反复清空体力；这个标记只在新局或停局后重置。</p>
     */
    private boolean roundInitialized = false;

    /**
     * 连续体力变化不会每 tick 立即发包，而是先记录脏状态，交给 serverTick 每隔几 tick
     * 给玩家本人做一次校准。归零、恢复和其它关键边界仍会立即同步。
     */
    private boolean syncDirty = false;

    public PlayerStaminaComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient()) {
            KEY.sync(this.player);
        }
    }

    /**
     * 体力组件只服务于玩家自己的 HUD 和输入限制，不需要广播给周围观察者。
     * 服务端 API 仍然可以读取任意目标的真实体力，收件人限制只影响客户端组件包。
     */
    @Override
    public boolean shouldSyncWith(ServerPlayerEntity recipient) {
        return recipient.equals(this.player);
    }

    /**
     * 清理本轮体力状态。
     *
     * <p>这里会同时清掉额外上限修正，避免某一局职业 / 词条临时加的体力上限残留到下一局。</p>
     */
    public void reset() {
        this.stamina = 0.0F;
        this.maxStaminaBonus = 0.0F;
        this.roundInitialized = false;
        this.syncDirty = false;
        this.sync();
    }

    public float getStamina() {
        return this.stamina;
    }

    /**
     * 设置当前体力值。
     *
     * <p>有限体力玩家会被钳制在 0 到当前有效上限之间；无限体力玩家只钳制下限，
     * 因为无限体力本身不会被 Wathe 的消耗逻辑读取，保留正数可供扩展自行展示或调试。</p>
     */
    public void setStamina(float stamina) {
        this.applyStamina(stamina, true);
    }

    /**
     * 仅供 Wathe 自己的玩家移动 tick 使用。
     * 每 tick 的消耗/恢复先进入脏标记，避免移动中的每个玩家持续刷 CCA 包。
     */
    public void setStaminaFromServerTick(float stamina) {
        this.applyStamina(stamina, false);
    }

    private void applyStamina(float stamina, boolean syncImmediately) {
        float clamped = Math.max(0.0F, stamina);
        float maxStamina = this.getMaxStamina();
        if (maxStamina >= 0.0F) {
            clamped = MathHelper.clamp(clamped, 0.0F, maxStamina);
        }

        if (Float.compare(this.stamina, clamped) != 0) {
            float oldStamina = this.stamina;
            this.stamina = clamped;
            boolean crossedZero = (oldStamina <= 0.0F) != (clamped <= 0.0F);
            boolean reachedMaximum = maxStamina >= 0.0F && Float.compare(clamped, maxStamina) == 0;
            if (syncImmediately || crossedZero || reachedMaximum) {
                this.syncDirty = false;
                this.sync();
            } else {
                this.syncDirty = true;
            }
        }
    }

    public void addStamina(float amount) {
        this.setStamina(this.stamina + Math.max(0.0F, amount));
    }

    public void addStaminaFromServerTick(float amount) {
        this.setStaminaFromServerTick(this.stamina + Math.max(0.0F, amount));
    }

    public void drainStamina(float amount) {
        this.setStamina(this.stamina - Math.max(0.0F, amount));
    }

    public void drainStaminaFromServerTick(float amount) {
        this.setStaminaFromServerTick(this.stamina - Math.max(0.0F, amount));
    }

    public void clearStamina() {
        this.setStamina(0.0F);
    }

    public void fillStamina() {
        float maxStamina = this.getMaxStamina();
        if (maxStamina >= 0.0F) {
            this.setStamina(maxStamina);
        }
    }

    public float getMaxStaminaBonus() {
        return this.maxStaminaBonus;
    }

    public void setMaxStaminaBonus(float maxStaminaBonus) {
        if (Float.compare(this.maxStaminaBonus, maxStaminaBonus) != 0) {
            this.maxStaminaBonus = maxStaminaBonus;
            float maxStamina = this.getMaxStamina();
            if (maxStamina >= 0.0F && this.stamina > maxStamina) {
                this.stamina = maxStamina;
            }
            this.syncDirty = false;
            this.sync();
        }
    }

    public void addMaxStaminaBonus(float amount) {
        this.setMaxStaminaBonus(this.maxStaminaBonus + amount);
    }

    public void resetMaxStaminaBonus() {
        this.setMaxStaminaBonus(0.0F);
    }

    /**
     * 读取当前职业提供的基础体力上限。
     *
     * <p>没有职业时返回 0，避免未分配身份的玩家在公开 API 里表现成“无限体力”。</p>
     */
    public float getBaseMaxStamina() {
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(this.player.getWorld());
        Role role = gameWorld.getRole(this.player);
        return role == null ? 0.0F : role.getMaxSprintTime();
    }

    /**
     * 读取当前有效体力上限。
     *
     * @return -1 表示无限体力；非负数表示有限体力的实际上限。
     */
    public float getMaxStamina() {
        float baseMaxStamina = this.getBaseMaxStamina();
        if (baseMaxStamina < 0.0F) {
            return -1.0F;
        }
        return Math.max(0.0F, baseMaxStamina + this.maxStaminaBonus);
    }

    public boolean hasFiniteStaminaLimit() {
        return this.getMaxStamina() >= 0.0F;
    }

    public boolean isExhausted() {
        return this.hasFiniteStaminaLimit() && this.stamina <= 0.0F;
    }

    public void clampStaminaToMax() {
        float maxStamina = this.getMaxStamina();
        if (maxStamina >= 0.0F && this.stamina > maxStamina) {
            this.stamina = maxStamina;
            this.syncDirty = false;
            this.sync();
        }
    }

    /**
     * 把连续体力值按固定周期校准给本人。服务端内部仍然每 tick 计算真实值，
     * 因此延迟只存在于客户端 HUD，不会影响移动、跳跃或任何服务端判定。
     */
    @Override
    public void serverTick() {
        if (this.syncDirty
                && this.player.getWorld().getTime() % GameConstants.PLAYER_STATE_SYNC_INTERVAL_TICKS == 0) {
            this.syncDirty = false;
            this.sync();
        }
    }

    public boolean isRoundInitialized() {
        return this.roundInitialized;
    }

    public void setRoundInitialized(boolean roundInitialized) {
        if (this.roundInitialized != roundInitialized) {
            this.roundInitialized = roundInitialized;
            this.sync();
        }
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.stamina = tag.contains("stamina", NbtElement.FLOAT_TYPE) ? Math.max(0.0F, tag.getFloat("stamina")) : 0.0F;
        this.maxStaminaBonus = tag.contains("maxStaminaBonus", NbtElement.FLOAT_TYPE) ? tag.getFloat("maxStaminaBonus") : 0.0F;
        this.roundInitialized = tag.contains("roundInitialized") && tag.getBoolean("roundInitialized");
        /*
         * 这里不能立刻按职业上限裁剪。
         * 读玩家组件时，世界组件里的本局职业表可能还没恢复完成；若此时把“无职业”当成 0 上限，
         * 会把旧存档里的真实体力直接压成 0。运行 tick 里拿到稳定职业后会再做一次安全裁剪。
         */
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putFloat("stamina", this.stamina);
        tag.putFloat("maxStaminaBonus", this.maxStaminaBonus);
        tag.putBoolean("roundInitialized", this.roundInitialized);
    }
}
