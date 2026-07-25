package dev.doctor4t.wathe.api.client.hud;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 通用 HUD provider 每帧拿到的只读上下文。
 *
 * <p>这里集中提供玩家、世界状态、存活状态、debug HUD 状态和热键栏复画能力。
 * 扩展职业迁移后不需要再在每个 mixin 里重复读取 {@link MinecraftClient}，
 * 也不需要各自猜 Wathe 的“存活玩家”定义。</p>
 */
@Environment(EnvType.CLIENT)
public record HudOverlayContext(
        @NotNull MinecraftClient client,
        @NotNull ClientPlayerEntity player,
        @NotNull TextRenderer textRenderer,
        @NotNull DrawContext drawContext,
        @NotNull RenderTickCounter tickCounter,
        @NotNull GameWorldComponent gameWorld,
        boolean aliveAndSurvival,
        boolean spectatingOrCreative,
        boolean debugHudVisible,
        boolean hudHidden,
        @Nullable Screen currentScreen,
        @Nullable HotbarRenderer hotbarRenderer
) {
    public int width() {
        return this.drawContext.getScaledWindowWidth();
    }

    public int height() {
        return this.drawContext.getScaledWindowHeight();
    }

    public float tickDelta() {
        return this.tickCounter.getTickDelta(true);
    }

    public boolean isRunning() {
        return this.gameWorld.isRunning();
    }

    public boolean isRole(@NotNull Role role) {
        return this.gameWorld.isRole(this.player, role);
    }

    public boolean isAliveRole(@NotNull Role role) {
        return this.aliveAndSurvival && this.isRole(role);
    }

    /**
     * 给“先盖全屏遮罩、再把热键栏画回来”的 HUD 使用。
     *
     * <p>典型场景是狙击镜：遮罩要画在所有 HUD 之后，但玩家仍需要看见当前手持栏。
     * 调用方不直接 mixin {@code InGameHud#renderHotbar}，而是让 Wathe 在上下文里提供一次受控复画。</p>
     */
    public void renderHotbar() {
        if (this.hotbarRenderer != null) {
            this.hotbarRenderer.render(this.drawContext, this.tickCounter);
        }
    }

    @FunctionalInterface
    public interface HotbarRenderer {
        void render(@NotNull DrawContext context, @NotNull RenderTickCounter tickCounter);
    }
}
