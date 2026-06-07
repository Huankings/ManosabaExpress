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
 * 保存玩家自己的“本能键输入模式”偏好。
 *
 * <p>这个设置必须挂在玩家身上，而不是挂在世界组件上：
 * 1. 不同玩家可以自由选择“按一下开关”或“长按生效”；
 * 2. 服务端指令只改执行者自己的组件，不会影响其他人；
 * 3. CCA 会把组件同步给对应客户端，客户端按键逻辑就能实时读取最新模式。</p>
 */
public class PlayerInstinctComponent implements AutoSyncedComponent {
    public static final ComponentKey<PlayerInstinctComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("instinct"), PlayerInstinctComponent.class);

    private static final String TOGGLE_MODE_KEY = "toggleModeEnabled";

    private final PlayerEntity player;

    /**
     * true：本能键为开关模式，按一下开启，再按一下关闭。
     * false：本能键为长按模式，只有按住时才认为输入生效。
     *
     * <p>默认值保持为 true，符合“默认开启开关模式”的需求。</p>
     */
    private boolean toggleModeEnabled = true;

    public PlayerInstinctComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient) {
            KEY.sync(this.player);
        }
    }

    public boolean isToggleModeEnabled() {
        return this.toggleModeEnabled;
    }

    public void setToggleModeEnabled(boolean toggleModeEnabled) {
        this.toggleModeEnabled = toggleModeEnabled;
        this.sync();
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putBoolean(TOGGLE_MODE_KEY, this.toggleModeEnabled);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        /*
         * 老存档没有这个字段时走默认 true。
         * 这样更新到新版后，玩家会自然进入“开关模式”，不会因为字段缺失退回长按。
         */
        this.toggleModeEnabled = !tag.contains(TOGGLE_MODE_KEY) || tag.getBoolean(TOGGLE_MODE_KEY);
    }
}
