package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Role;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/**
 * 玩家体力组件。
 *
 * <p>旧版体力值直接存在 {@code PlayerEntityMixin} 的私有字段里，扩展模组想读写体力只能继续写 mixin。
 * 现在把“当前体力、额外体力上限修正、单局初始化标记”统一迁到 CCA 组件，公开 API 再基于这个组件读写。
 * 这样扩展职业可以直接清空、回满、增减体力或调整体力上限，而不需要碰玩家实体内部字段。</p>
 */
public class PlayerStaminaComponent implements AutoSyncedComponent {
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

    public PlayerStaminaComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient()) {
            KEY.sync(this.player);
        }
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
        float clamped = Math.max(0.0F, stamina);
        float maxStamina = this.getMaxStamina();
        if (maxStamina >= 0.0F) {
            clamped = MathHelper.clamp(clamped, 0.0F, maxStamina);
        }

        if (Float.compare(this.stamina, clamped) != 0) {
            this.stamina = clamped;
            this.sync();
        }
    }

    public void addStamina(float amount) {
        this.setStamina(this.stamina + Math.max(0.0F, amount));
    }

    public void drainStamina(float amount) {
        this.setStamina(this.stamina - Math.max(0.0F, amount));
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
            this.clampStaminaToMax();
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
