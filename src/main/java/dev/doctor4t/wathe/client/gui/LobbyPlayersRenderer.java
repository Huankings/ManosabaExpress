package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.cca.AutoStartComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LobbyPlayersRenderer {
    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context) {
        GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
        if (!game.isRunning()) {
            context.getMatrices().push();
            context.getMatrices().translate(context.getScaledWindowWidth() / 2f, 6, 0);
            World world = player.getWorld();
            List<? extends PlayerEntity> players = world.getPlayers();
            int count = players.size();
            int readyPlayerCount = GameFunctions.getReadyPlayerCount(world);
            MutableText playerCountText = Text.translatable("lobby.players.count", readyPlayerCount, count);
            context.drawTextWithShadow(renderer, playerCountText, -renderer.getWidth(playerCountText) / 2, 0, 0xFFFFFFFF);

            AutoStartComponent autoStartComponent = AutoStartComponent.KEY.get(world);
            if (autoStartComponent.isAutoStartActive()) {
                MutableText autoStartText;
                int color = 0xFFAAAAAA;
                if (readyPlayerCount >= game.getGameMode().minPlayerCount) {
                    int seconds = autoStartComponent.getTime() / 20;
                    autoStartText = Text.translatable(seconds <= 0 ? "lobby.autostart.starting" : "lobby.autostart.time", seconds);
                    color = 0xFF00BC16;
                } else {
                    autoStartText = Text.translatable("lobby.autostart.active");
                }
                context.drawTextWithShadow(renderer, autoStartText, -renderer.getWidth(autoStartText) / 2, 10, color);
            }

            context.getMatrices().pop();

            context.getMatrices().push();
            float scale = 0.75f;
            context.getMatrices().translate(0, context.getScaledWindowHeight(), 0);
            context.getMatrices().scale(scale, scale, 1f);
            int i = 0;
            MutableText thanksText = Text.translatable("credits.wathe.thank_you");

            String fallback = "感谢你游玩《魔女岛-最后航程》！\n我和我的团队负责了地图的搭建，我负责了游戏的一些魔改开发，希望你喜欢它。\n如果你喜欢魔女岛并且想要制作视频或直播，\n请务必在视频中标注我的频道！\n同时，别忘了支持原作者RAT / doctor4t并赞助他们给他们经济支持\n - 幻影丘 / Annina";
            if (!thanksText.getString().contains(" - 幻影丘 / Annina")) {
                thanksText = Text.literal(fallback);
            }

            for (Text text : TextUtils.getWithLineBreaks(thanksText)) {
                i++;
                context.drawTextWithShadow(renderer, text, 10, -90 + 10 * i, 0xFFFFFFFF);
            }
            context.getMatrices().pop();
        }
    }
}