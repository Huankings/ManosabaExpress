package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

public class PlayerGrenadeComponent implements AutoSyncedComponent {
    public static final ComponentKey<PlayerGrenadeComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("grenade_throw_mode"), PlayerGrenadeComponent.class);

    private final PlayerEntity player;
    private ThrowMode throwMode = ThrowMode.CHARGED;

    public PlayerGrenadeComponent(PlayerEntity player) {
        this.player = player;
    }

    public void sync() {
        if (!this.player.getWorld().isClient) {
            KEY.sync(this.player);
        }
    }

    public @NotNull ThrowMode getThrowMode() {
        return this.throwMode;
    }

    public boolean isDirectThrowMode() {
        return this.throwMode == ThrowMode.DIRECT;
    }

    public @NotNull ThrowMode setThrowMode(@NotNull ThrowMode throwMode) {
        return this.setThrowMode(throwMode, true);
    }

    public @NotNull ThrowMode setThrowModeLocal(@NotNull ThrowMode throwMode) {
        return this.setThrowMode(throwMode, false);
    }

    public @NotNull ThrowMode toggle() {
        return this.setThrowMode(this.throwMode.toggle(), true);
    }

    public @NotNull ThrowMode toggleLocal() {
        return this.setThrowMode(this.throwMode.toggle(), false);
    }

    private @NotNull ThrowMode setThrowMode(@NotNull ThrowMode throwMode, boolean shouldSync) {
        this.throwMode = throwMode;
        if (shouldSync) {
            this.sync();
        }
        return this.throwMode;
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putString("throwMode", this.throwMode.getSerializedName());
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.throwMode = ThrowMode.fromSerializedName(tag.getString("throwMode"));
    }

    public enum ThrowMode {
        DIRECT("direct", "tip.grenade.throw_mode.direct", Formatting.GREEN),
        CHARGED("charged", "tip.grenade.throw_mode.charged", Formatting.RED);

        private final String serializedName;
        private final String translationKey;
        private final Formatting formatting;

        ThrowMode(String serializedName, String translationKey, Formatting formatting) {
            this.serializedName = serializedName;
            this.translationKey = translationKey;
            this.formatting = formatting;
        }

        public @NotNull ThrowMode toggle() {
            return this == DIRECT ? CHARGED : DIRECT;
        }

        public @NotNull String getSerializedName() {
            return this.serializedName;
        }

        public @NotNull MutableText getDisplayText() {
            return Text.translatable(this.translationKey).formatted(this.formatting);
        }

        public static @NotNull ThrowMode fromDirectThrow(boolean directThrow) {
            return directThrow ? DIRECT : CHARGED;
        }

        public static @NotNull ThrowMode fromSerializedName(String serializedName) {
            for (ThrowMode value : values()) {
                if (value.serializedName.equalsIgnoreCase(serializedName)) {
                    return value;
                }
            }
            return CHARGED;
        }
    }
}
