package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.ratatouille.util.TextUtils;
import dev.doctor4t.wathe.cca.AutoStartComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.client.gui.screen.MapVotingScreen;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.MinecraftClient;
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
    private static final int TOP_TEXT_Y = 6;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_GOLD = 0xFFC5A244;

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context) {
        GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
        if (!game.isRunning()) {
            World world = player.getWorld();
            MapVotingComponent voting = MapVotingComponent.KEY.get(world.getScoreboard());
            boolean isVotingScreenOpen = MinecraftClient.getInstance().currentScreen instanceof MapVotingScreen;

            if (voting.isVotingActive() && !isVotingScreenOpen) {
                renderVotingPrompt(renderer, context, voting);
            } else if (!voting.isVotingActive()) {
                renderLobbyPrompt(renderer, context, game, world);
            }

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

    private static void renderVotingPrompt(TextRenderer renderer, @NotNull DrawContext context, @NotNull MapVotingComponent voting) {
        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, TOP_TEXT_Y, 0);

        int keybindHintY;
        if (!voting.isRoulettePhase()) {
            int secondsRemaining = Math.max(0, voting.getVotingTicksRemaining() / 20);
            String timeString = String.format("%d:%02d", secondsRemaining / 60, secondsRemaining % 60);
            Text timerText = Text.literal(timeString);

            context.getMatrices().push();
            context.getMatrices().scale(1.5f, 1.5f, 1f);
            context.drawTextWithShadow(renderer, timerText, -renderer.getWidth(timerText) / 2, 0, getTimerColor(secondsRemaining));
            context.getMatrices().pop();

            MutableText votingText = Text.translatable("lobby.voting.active");
            context.drawTextWithShadow(renderer, votingText, -renderer.getWidth(votingText) / 2, 16, COLOR_GOLD);
            keybindHintY = 28;
        } else {
            MutableText selectingText = Text.translatable("gui.wathe.map_voting.selecting");
            context.drawTextWithShadow(renderer, selectingText, -renderer.getWidth(selectingText) / 2, 0, COLOR_GOLD);
            keybindHintY = 12;
        }

        MutableText keybindHint = Text.translatable("lobby.voting.keybind_hint", getMapVoteKeyName());
        context.drawTextWithShadow(renderer, keybindHint, -renderer.getWidth(keybindHint) / 2, keybindHintY, COLOR_DIM);

        context.getMatrices().pop();
    }

    private static void renderLobbyPrompt(TextRenderer renderer, @NotNull DrawContext context, GameWorldComponent game, World world) {
        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, TOP_TEXT_Y, 0);

        List<? extends PlayerEntity> players = world.getPlayers();
        int count = players.size();
        int readyPlayerCount = GameFunctions.getReadyPlayerCount(world);
        MutableText playerCountText = Text.translatable("lobby.players.count", readyPlayerCount, count);
        context.drawTextWithShadow(renderer, playerCountText, -renderer.getWidth(playerCountText) / 2, 0, COLOR_WHITE);

        AutoStartComponent autoStartComponent = AutoStartComponent.KEY.get(world);
        if (autoStartComponent.isAutoStartActive()) {
            MutableText autoStartText;
            int color = COLOR_DIM;
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
    }

    private static int getTimerColor(int secondsRemaining) {
        if (secondsRemaining > 10) {
            return COLOR_GOLD;
        }

        // 最后十秒做一个轻微闪烁，提醒玩家投票即将结束。
        float flash = (float) (0.5f + 0.5f * Math.sin(System.currentTimeMillis() / 150.0));
        int red = (int) (255 * (0.6f + 0.4f * flash));
        return 0xFF000000 | (red << 16) | (0x30 << 8) | 0x30;
    }

    private static String getMapVoteKeyName() {
        if (WatheClient.mapVoteKeybind == null) {
            return "H";
        }
        return WatheClient.mapVoteKeybind.getBoundKeyLocalizedText().getString();
    }
}
