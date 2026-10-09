package dev.doctor4t.wathe.client.appearance;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.spectator.SpectatorMenu;
import net.minecraft.client.gui.hud.spectator.SpectatorMenuCommand;
import net.minecraft.text.Text;

/** 只包装旁观菜单的显示名称，所有真实 use/render 行为仍交给原命令。 */
public final class DebugIdentitySpectatorCommand implements SpectatorMenuCommand {
    private final SpectatorMenuCommand delegate;
    private final Text name;

    public DebugIdentitySpectatorCommand(SpectatorMenuCommand delegate, Text name) {
        this.delegate = delegate;
        this.name = name;
    }

    @Override
    public void use(SpectatorMenu menu) {
        this.delegate.use(menu);
    }

    @Override
    public Text getName() {
        return this.name;
    }

    @Override
    public void renderIcon(DrawContext context, float brightness, int alpha) {
        this.delegate.renderIcon(context, brightness, alpha);
    }

    @Override
    public boolean isEnabled() {
        return this.delegate.isEnabled();
    }
}
