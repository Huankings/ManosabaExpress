package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 背包玩家头像分页的默认布局。
 *
 * <p>这些数值来自 NoellesRoles 已经验证过的一套布局：每页最多 10 人、间距 36、
 * 最后一页按实际显示内容重新居中。放到 Wathe 后，扩展职业可以复用同一套坐标，
 * 不需要每个 mod 都复制一个 ScreenMixin 和分页工具。</p>
 */
@Environment(EnvType.CLIENT)
public final class InventoryButtonLayout {
    public static final int PLAYERS_PER_PAGE = 10;
    public static final int SLOT_APART = 36;
    public static final int SLOT_X_OFFSET = 9;

    private InventoryButtonLayout() {
    }

    public static int getPlayerRowY(int screenHeight) {
        return (screenHeight - 32) / 2 + 80;
    }

    public static int getCenteredPlayerStartX(int screenWidth, int visiblePlayerCount) {
        return screenWidth / 2 - visiblePlayerCount * SLOT_APART / 2 + SLOT_X_OFFSET;
    }

    public static int getCenteredGroupStartX(int screenWidth, int visiblePlayerCount, boolean showPrevious, boolean showNext) {
        int buttonCount = (showPrevious ? 1 : 0) + (showNext ? 1 : 0);
        int totalSlots = visiblePlayerCount + buttonCount;
        return screenWidth / 2 - totalSlots * SLOT_APART / 2 + SLOT_X_OFFSET;
    }

    public static int getTotalPageCount(int totalPlayers) {
        return Math.max(1, (totalPlayers + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
    }
}
