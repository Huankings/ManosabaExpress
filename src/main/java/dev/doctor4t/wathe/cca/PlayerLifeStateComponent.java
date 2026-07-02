package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

/**
 * 玩家玩法生命状态组件。
 *
 * <p>Wathe 原本把“局内存活”完全绑定到原版游戏模式：
 * 只要玩家是 creative 或 spectator，就会被所有玩法逻辑当成非存活。
 * 这个组件只保存一个很小的覆盖标记，用来表达：
 * “该玩家虽然处于 creative / spectator，但这是职业或玩法机制授权的特殊状态，
 * 因此玩法层仍然要把他当作存活玩家”。</p>
 *
 * <p>注意：这里不改变原版游戏模式本身，也不限制创造权限。
 * 它只影响 Wathe 自己通过 {@code GameFunctions} 暴露的存活 / 非存活判定。</p>
 */
public class PlayerLifeStateComponent implements AutoSyncedComponent {
    public static final ComponentKey<PlayerLifeStateComponent> KEY =
            ComponentRegistry.getOrCreate(Wathe.id("life_state"), PlayerLifeStateComponent.class);

    private static final String ALIVE_IN_NON_SURVIVAL_MODE_KEY = "AliveInNonSurvivalMode";

    private final PlayerEntity player;

    /**
     * true 表示玩家在 creative / spectator 中仍按“局内存活”处理。
     *
     * <p>这个字段只是一枚授权标记；真正判定时仍会检查玩家当前是否处于
     * creative 或 spectator，避免标记残留到 adventure / survival 后又被下一次普通切模式误用。</p>
     */
    private boolean aliveInNonSurvivalMode = false;

    public PlayerLifeStateComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient) {
            KEY.sync(this.player);
        }
    }

    public boolean isAliveInNonSurvivalMode() {
        return this.aliveInNonSurvivalMode;
    }

    public void setAliveInNonSurvivalMode(boolean aliveInNonSurvivalMode) {
        if (this.aliveInNonSurvivalMode == aliveInNonSurvivalMode) {
            return;
        }

        this.aliveInNonSurvivalMode = aliveInNonSurvivalMode;
        this.sync();
    }

    public void clearAliveInNonSurvivalMode() {
        this.setAliveInNonSurvivalMode(false);
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putBoolean(ALIVE_IN_NON_SURVIVAL_MODE_KEY, this.aliveInNonSurvivalMode);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.aliveInNonSurvivalMode = tag.getBoolean(ALIVE_IN_NON_SURVIVAL_MODE_KEY);
    }
}
