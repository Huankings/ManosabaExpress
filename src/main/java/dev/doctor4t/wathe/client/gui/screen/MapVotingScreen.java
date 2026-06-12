package dev.doctor4t.wathe.client.gui.screen;

import dev.doctor4t.wathe.cca.MapVotingComponent;
import dev.doctor4t.wathe.cca.MapVotingComponent.UnavailableMapEntry;
import dev.doctor4t.wathe.cca.MapVotingComponent.VotingMapEntry;
import dev.doctor4t.wathe.util.MapVotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.Random;

/**
 * 地图投票界面。
 *
 * <p>非 OP 在 onlyop=true 时仍可打开界面查看候选地图，但点击不会发送投票包。</p>
 */
public class MapVotingScreen extends Screen {
    private static final int BG_TOP = 0xEE1A0505;
    private static final int BG_BOTTOM = 0xEE0F0202;
    private static final int BRASS_COLOR = 0xFFD4AF37;
    private static final int BRASS_DIM = 0xFF8B735B;
    private static final int TICKET_BG = 0xFFFDF5E6;
    private static final int TICKET_BG_DARK = 0xFFE0D5C0;
    private static final int TICKET_BG_SELECTED = 0xFFFFF8F0;
    private static final int TEXT_INK = 0xFF2F1B1B;
    private static final int TEXT_DIM = 0xFF6B5A5A;
    private static final int TEXT_RED = 0xFFA00000;
    private static final int BAR_BG = 0xFF4A3520;
    private static final int BAR_FILL = 0xFFA00000;

    private static final int BASE_CARD_WIDTH = 140;
    private static final int BASE_CARD_HEIGHT = 125;
    private static final int BASE_CARD_GAP = 12;
    private static final int TARGET_COLS = 5;
    private static final int TARGET_ROWS = 2;

    private int cardWidth = BASE_CARD_WIDTH;
    private int cardHeight = BASE_CARD_HEIGHT;
    private int cardGap = BASE_CARD_GAP;
    private int layoutCols = TARGET_COLS;
    private int layoutRows = TARGET_ROWS;
    private int layoutTopY = 55;
    private int layoutPadding = 30;

    private static final int STRIP_CARD_WIDTH = 120;
    private static final int STRIP_CARD_HEIGHT = 80;
    private static final int STRIP_TOTAL_CARDS = 40;
    private static final int STRIP_LANDING_POS = STRIP_TOTAL_CARDS - 8;
    private static final int ROULETTE_SCROLL_TICKS = 7 * 20;

    private float slideProgress = 0f;
    private float prevSlideProgress = 0f;
    private static final float SLIDE_SPEED = 0.08f;

    private int scrollRow = 0;
    private int maxScrollRow = 0;

    private int[] rouletteSequence;
    private int rouletteAnimTick = 0;
    private boolean rouletteStripInitialized = false;
    private boolean rouletteResultSoundPlayed = false;
    private int lastRouletteTickIndex = -1;

    public MapVotingScreen() {
        super(Text.translatable("gui.wathe.map_voting.title"));
    }

    @Override
    protected void init() {
        super.init();
        computeLayout();
    }

    private void computeLayout() {
        layoutPadding = 30;
        layoutTopY = 55;

        int bottomY = height - 45;
        int availableWidth = width - layoutPadding * 2;
        int availableHeight = bottomY - layoutTopY;

        layoutCols = TARGET_COLS;
        while (layoutCols > 1) {
            int testWidth = (availableWidth - (layoutCols - 1) * BASE_CARD_GAP) / layoutCols;
            if (testWidth >= 100) break;
            layoutCols--;
        }

        cardGap = BASE_CARD_GAP;
        cardWidth = (availableWidth - (layoutCols - 1) * cardGap) / layoutCols;
        cardWidth = Math.min(cardWidth, 180);

        layoutRows = TARGET_ROWS;
        while (layoutRows > 1) {
            int testHeight = (availableHeight - (layoutRows - 1) * cardGap) / layoutRows;
            if (testHeight >= 90) break;
            layoutRows--;
        }

        cardHeight = (availableHeight - (layoutRows - 1) * cardGap) / layoutRows;
        cardHeight = Math.min(cardHeight, 150);
        scrollRow = 0;
    }

    private void updateMaxScroll(int totalCards) {
        int totalRows = (totalCards + layoutCols - 1) / layoutCols;
        maxScrollRow = Math.max(0, totalRows - layoutRows);
        scrollRow = MathHelper.clamp(scrollRow, 0, maxScrollRow);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        MapVotingComponent voting = getVoting();
        return voting == null || !voting.isRoulettePhase();
    }

    private MapVotingComponent getVoting() {
        if (client != null && client.world != null) {
            return MapVotingComponent.KEY.get(client.world.getScoreboard());
        }
        return null;
    }

    private boolean canLocalPlayerVote(MapVotingComponent voting) {
        if (!voting.isOnlyOpVoting()) {
            return true;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.hasPermissionLevel(2);
    }

    @Override
    public void tick() {
        super.tick();
        prevSlideProgress = slideProgress;
        if (slideProgress < 1f) {
            slideProgress = Math.min(1f, slideProgress + SLIDE_SPEED);
        }

        MapVotingComponent voting = getVoting();
        if (voting != null && voting.isRoulettePhase()) {
            if (!rouletteStripInitialized) {
                initRouletteStrip(voting.getSelectedMapIndex(), voting.getAvailableMaps().size());
                rouletteStripInitialized = true;
                rouletteResultSoundPlayed = false;
                lastRouletteTickIndex = -1;
                rouletteAnimTick = 0;
            }
            rouletteAnimTick++;
        } else {
            rouletteStripInitialized = false;
            rouletteAnimTick = 0;
        }
    }

    private void initRouletteStrip(int selectedMapIndex, int mapCount) {
        if (mapCount <= 0) return;
        rouletteSequence = new int[STRIP_TOTAL_CARDS];
        Random random = new Random();

        for (int i = 0; i < STRIP_TOTAL_CARDS; i++) {
            if (i == STRIP_LANDING_POS) {
                rouletteSequence[i] = selectedMapIndex;
            } else if (i > STRIP_LANDING_POS && i < STRIP_LANDING_POS + 3) {
                rouletteSequence[i] = random.nextInt(mapCount);
            } else {
                int next = i % mapCount;
                if (i == STRIP_LANDING_POS - 1 && next == selectedMapIndex) {
                    next = (next + 1) % mapCount;
                }
                rouletteSequence[i] = next;
            }
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MapVotingComponent voting = getVoting();
        if (voting == null || !voting.isVotingActive()) return;

        float smoothedSlide = MathHelper.lerp(delta, prevSlideProgress, slideProgress);
        float ease = easeOutBack(smoothedSlide);
        int yOffset = (int) ((1f - ease) * 100);

        context.fillGradient(0, 0, this.width, this.height, BG_TOP, BG_BOTTOM);
        renderDecoBars(context, yOffset);

        List<VotingMapEntry> maps = voting.getAvailableMaps();
        if (voting.isRoulettePhase()) {
            renderRouletteStrip(context, maps, delta, yOffset);
            drawTitle(context, Text.translatable("gui.wathe.map_voting.selecting"), 20 - yOffset, 1.5f);
        } else {
            renderVotingCards(context, voting, maps, mouseX, mouseY, yOffset);
            drawTitle(context, Text.translatable("gui.wathe.map_voting.title"), 16 - yOffset, 1.2f);

            int ticksLeft = voting.getVotingTicksRemaining();
            String timeStr = String.format("%d", Math.max(0, ticksLeft / 20));
            int color = ticksLeft < 100 ? 0xFFFF5555 : BRASS_COLOR;
            drawCenteredText(context, timeStr, this.width / 2, 30 - yOffset, color);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderDecoBars(DrawContext context, int yOffset) {
        context.fill(0, 45 - yOffset, width, 46 - yOffset, BRASS_COLOR);
        context.fill(0, 48 - yOffset, width, 49 - yOffset, BRASS_COLOR);
        context.fill(0, height - 40 + yOffset, width, height - 39 + yOffset, BRASS_COLOR);
        context.fill(0, height - 43 + yOffset, width, height - 42 + yOffset, BRASS_COLOR);
    }

    private void drawTitle(DrawContext context, Text text, int y, float scale) {
        context.getMatrices().push();
        context.getMatrices().translate(width / 2f, y, 0);
        context.getMatrices().scale(scale, scale, 1);
        drawCenteredText(context, text, 0, 0, BRASS_COLOR);
        context.getMatrices().pop();
    }

    private void renderVotingCards(DrawContext context, MapVotingComponent voting, List<VotingMapEntry> maps, int mouseX, int mouseY, int yOffset) {
        List<UnavailableMapEntry> unavailableMaps = voting.getUnavailableMaps();
        int[] voteCounts = voting.getVoteCounts();

        int totalCards = maps.size() + unavailableMaps.size();
        updateMaxScroll(totalCards);

        int gridWidth = layoutCols * cardWidth + (layoutCols - 1) * cardGap;
        int startX = (this.width - gridWidth) / 2;
        int baseY = layoutTopY - yOffset;

        int totalVotes = 0;
        for (int count : voteCounts) totalVotes += count;
        int myVote = (client.player != null) ? voting.getVotedMapIndex(client.player.getUuid()) : -1;

        boolean hasAnyVotes = false;
        for (int count : voteCounts) {
            if (count > 0) {
                hasAnyVotes = true;
                break;
            }
        }

        int totalWeight = 0;
        int[] weights = new int[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            weights[i] = hasAnyVotes ? (i < voteCounts.length ? voteCounts[i] : 0) : 1;
            totalWeight += weights[i];
        }

        int firstVisibleIndex = scrollRow * layoutCols;
        int lastVisibleIndex = firstVisibleIndex + layoutRows * layoutCols;

        for (int index = firstVisibleIndex; index < totalCards && index < lastVisibleIndex; index++) {
            int visibleIndex = index - firstVisibleIndex;
            int col = visibleIndex % layoutCols;
            int row = visibleIndex / layoutCols;

            int x = startX + col * (cardWidth + cardGap);
            int y = baseY + row * (cardHeight + cardGap);

            if (index < maps.size()) {
                VotingMapEntry map = maps.get(index);
                boolean isHovered = mouseX >= x && mouseX <= x + cardWidth && mouseY >= y && mouseY <= y + cardHeight;
                boolean isMyVote = index == myVote;
                int hoverOffset = isHovered && canLocalPlayerVote(voting) ? -3 : 0;
                float probability = totalWeight > 0 ? (float) weights[index] / totalWeight : 0f;

                drawTicketCard(context, x, y + hoverOffset, map, true, isMyVote, isHovered,
                        index < voteCounts.length ? voteCounts[index] : 0, totalVotes, probability);
            } else {
                int unavailableIndex = index - maps.size();
                UnavailableMapEntry unavailableMap = unavailableMaps.get(unavailableIndex);
                drawTicketCard(context, x, y, new VotingMapEntry(unavailableMap.dimensionId(), unavailableMap.displayName(), "", 0, 0),
                        false, false, false, 0, 0, 0f);
                context.fill(x + 5, y + 5, x + cardWidth - 5, y + cardHeight - 5, 0xAA000000);
                context.drawBorder(x + 5, y + 5, cardWidth - 10, cardHeight - 10, 0x55FFFFFF);
                drawCenteredText(context, parseUnavailableReason(unavailableMap.reason()), x + cardWidth / 2, y + cardHeight / 2 - 4, 0xFFAAAAAA);
            }
        }

        renderBottomStatus(context, voting, yOffset);
    }

    private void renderBottomStatus(DrawContext context, MapVotingComponent voting, int yOffset) {
        int indicatorY = height - 35;
        if (maxScrollRow > 0) {
            String pageText = (scrollRow + 1) + " / " + (maxScrollRow + 1);
            drawCenteredText(context, Text.literal(pageText), width / 2, indicatorY, BRASS_DIM);

            if (scrollRow > 0) {
                drawTriangle(context, width / 2, layoutTopY - 10 - yOffset, 5, BRASS_COLOR, false);
            }
            if (scrollRow < maxScrollRow) {
                drawTriangle(context, width / 2, height - 48, 5, BRASS_COLOR, true);
            }
        }

        if (voting.isOnlyOpVoting() && !canLocalPlayerVote(voting)) {
            drawCenteredText(context, Text.literal("当前仅管理员可投票"), width / 2, indicatorY + 12, 0xFFFFD080);
        }
    }

    private void drawTicketCard(DrawContext context, int x, int y, VotingMapEntry map,
                                boolean available, boolean selected, boolean hovered, int votes, int totalVotes, float probability) {
        int cw = cardWidth;
        int ch = cardHeight;
        int bgColor = selected ? TICKET_BG_SELECTED : (available ? TICKET_BG : 0xFF504040);

        context.fill(x + 4, y + 4, x + cw + 4, y + ch + 4, 0x66000000);
        context.fillGradient(x, y, x + cw, y + ch, bgColor, available ? TICKET_BG_DARK : 0xFF302020);

        int borderColor = selected ? BRASS_COLOR : (hovered ? 0xFF8B4513 : TEXT_INK);
        if (!available) borderColor = 0xFF2A2A2A;
        context.drawBorder(x + 2, y + 2, cw - 4, ch - 4, borderColor);
        context.drawBorder(x + 5, y + 5, cw - 10, ch - 10, borderColor & 0x88FFFFFF);

        context.fill(x + 6, y + 6, x + 8, y + 8, BRASS_DIM);
        context.fill(x + cw - 8, y + 6, x + cw - 6, y + 8, BRASS_DIM);
        context.fill(x + 6, y + ch - 8, x + 8, y + ch - 6, BRASS_DIM);
        context.fill(x + cw - 8, y + ch - 8, x + cw - 6, y + ch - 6, BRASS_DIM);

        int titleColor = available ? TEXT_INK : 0xFFAAAAAA;
        drawCenteredText(context, Text.literal(map.displayName()), x + cw / 2, y + 15, titleColor);

        if (available) {
            drawCenteredText(context, Text.translatable("gui.wathe.map_voting.player_range", map.minPlayers(), map.maxPlayers()), x + cw / 2, y + 30, TEXT_DIM);

            int barWidth = cw - 24;
            int barX = x + 12;
            int barY = y + ch - 25;

            int descStartY = y + 45;
            int lineHeight = 10;
            int descAvailableHeight = barY - descStartY - 16;
            int maxLines = Math.max(1, descAvailableHeight / lineHeight);
            List<OrderedText> descLines = textRenderer.wrapLines(StringVisitable.plain(map.description()), cw - 20);
            int linesToRender = Math.min(descLines.size(), maxLines);
            for (int line = 0; line < linesToRender; line++) {
                OrderedText orderedText = descLines.get(line);
                int lineWidth = textRenderer.getWidth(orderedText);
                context.drawText(textRenderer, orderedText, x + (cw - lineWidth) / 2, descStartY + line * lineHeight, TEXT_DIM, false);
            }

            int probY = descStartY + linesToRender * lineHeight + 2;
            String probStr = String.format("%.1f%%", probability * 100f);
            drawCenteredText(context, Text.literal(probStr), x + cw / 2, probY, probability >= 0.5f ? TEXT_RED : BRASS_DIM);

            context.fill(barX, barY, barX + barWidth, barY + 4, BAR_BG);
            if (totalVotes > 0 && votes > 0) {
                int fillW = (int) (((float) votes / totalVotes) * barWidth);
                context.fill(barX, barY, barX + fillW, barY + 4, BAR_FILL);
            }

            Text voteText = Text.translatable("gui.wathe.map_voting.votes", votes);
            context.getMatrices().push();
            context.getMatrices().translate(x + cw / 2f, barY + 8, 0);
            context.getMatrices().scale(0.8f, 0.8f, 1);
            drawCenteredText(context, voteText, 0, 0, TEXT_DIM);
            context.getMatrices().pop();

            if (selected) {
                context.drawBorder(x + cw - 30, y + ch - 30, 24, 24, 0xFFA00000);
                context.getMatrices().push();
                context.getMatrices().translate(x + cw - 18, y + ch - 18, 0);
                context.getMatrices().scale(0.6f, 0.6f, 1);
                context.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(-15));
                drawCenteredText(context, Text.translatable("gui.wathe.map_voting.voted_stamp"), 0, 0, 0xFFA00000);
                context.getMatrices().pop();
            }
        }
    }

    private void renderRouletteStrip(DrawContext context, List<VotingMapEntry> maps, float delta, int yOffset) {
        if (rouletteSequence == null || maps.isEmpty()) return;

        int centerY = this.height / 2;
        int centerX = this.width / 2;

        float smoothTick = rouletteAnimTick + delta;
        float progress = Math.min(1.0f, smoothTick / ROULETTE_SCROLL_TICKS);
        float ease;
        if (progress < 0.15f) {
            float t = progress / 0.15f;
            ease = 0.15f * t * t;
        } else {
            float t = (progress - 0.15f) / 0.85f;
            ease = 0.15f + 0.85f * (1f - (1f - t) * (1f - t) * (1f - t));
        }

        float totalScrollPixels = STRIP_LANDING_POS * (STRIP_CARD_WIDTH + 8) + STRIP_CARD_WIDTH / 2f;
        float currentScroll = totalScrollPixels * ease;

        int currentCardIndex = (int) (currentScroll / (STRIP_CARD_WIDTH + 8));
        if (currentCardIndex != lastRouletteTickIndex && currentCardIndex >= 0 && progress < 0.97f) {
            lastRouletteTickIndex = currentCardIndex;
            float pitchBase = 0.8f + 0.6f * progress;
            float volume = 0.3f + 0.4f * progress;
            playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), volume, pitchBase);
        }

        int stripHeight = STRIP_CARD_HEIGHT + 20;
        context.enableScissor(0, centerY - stripHeight / 2, width, centerY + stripHeight / 2);
        context.fill(0, centerY - stripHeight / 2, width, centerY + stripHeight / 2, 0xAA000000);
        context.fill(0, centerY - 1, width, centerY + 1, 0x44D4AF37);

        for (int i = 0; i < rouletteSequence.length; i++) {
            int mapIndex = rouletteSequence[i];
            if (mapIndex < 0 || mapIndex >= maps.size()) continue;

            float itemX = i * (STRIP_CARD_WIDTH + 8) + STRIP_CARD_WIDTH / 2f;
            float drawX = centerX + itemX - currentScroll - STRIP_CARD_WIDTH / 2f;
            if (drawX > width || drawX + STRIP_CARD_WIDTH < 0) continue;

            VotingMapEntry map = maps.get(mapIndex);
            float distToCenter = Math.abs((drawX + STRIP_CARD_WIDTH / 2f) - centerX);
            float scale = 1.0f - MathHelper.clamp(distToCenter / 300f, 0f, 0.2f);
            boolean isWinner = i == STRIP_LANDING_POS && progress >= 0.97f;

            if (isWinner && !rouletteResultSoundPlayed) {
                rouletteResultSoundPlayed = true;
                playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }

            renderRouletteCard(context, (int) drawX, centerY - STRIP_CARD_HEIGHT / 2, map, scale, isWinner, smoothTick);
        }

        context.disableScissor();
        drawTriangle(context, centerX, centerY - stripHeight / 2, 8, BRASS_COLOR, false);
        drawTriangle(context, centerX, centerY + stripHeight / 2, 8, BRASS_COLOR, true);
    }

    private void renderRouletteCard(DrawContext context, int x, int y, VotingMapEntry map, float scale, boolean isWinner, float time) {
        context.getMatrices().push();
        context.getMatrices().translate(x + STRIP_CARD_WIDTH / 2f, y + STRIP_CARD_HEIGHT / 2f, 0);
        context.getMatrices().scale(scale, scale, 1f);
        context.getMatrices().translate(-STRIP_CARD_WIDTH / 2f, -STRIP_CARD_HEIGHT / 2f, 0);

        int borderColor = isWinner ? (Math.sin(time * 0.5) > 0 ? 0xFFFFD700 : BRASS_COLOR) : 0xFF5A4A4A;
        context.fill(0, 0, STRIP_CARD_WIDTH, STRIP_CARD_HEIGHT, TICKET_BG);
        context.drawBorder(0, 0, STRIP_CARD_WIDTH, STRIP_CARD_HEIGHT, borderColor);
        context.drawBorder(3, 3, STRIP_CARD_WIDTH - 6, STRIP_CARD_HEIGHT - 6, borderColor & 0x88FFFFFF);

        drawCenteredText(context, Text.literal(map.displayName()), STRIP_CARD_WIDTH / 2, 10, TEXT_INK);
        drawCenteredText(context, Text.translatable("gui.wathe.map_voting.player_range", map.minPlayers(), map.maxPlayers()), STRIP_CARD_WIDTH / 2, 25, TEXT_DIM);

        context.getMatrices().pop();
    }

    private void drawTriangle(DrawContext context, int x, int y, int size, int color, boolean pointDown) {
        int direction = pointDown ? 1 : -1;
        for (int i = 0; i < size; i++) {
            context.fill(x - i, y + i * direction, x + i + 1, y + (i + 1) * direction, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MapVotingComponent voting = getVoting();
        if (voting == null || !voting.isVotingActive() || voting.isRoulettePhase() || !canLocalPlayerVote(voting)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        List<VotingMapEntry> maps = voting.getAvailableMaps();
        int yOffset = (int) ((1f - easeOutBack(Math.min(1f, slideProgress))) * 100);

        int gridWidth = layoutCols * cardWidth + (layoutCols - 1) * cardGap;
        int startX = (this.width - gridWidth) / 2;
        int baseY = layoutTopY - yOffset;

        int firstVisibleIndex = scrollRow * layoutCols;
        int lastVisibleIndex = Math.min(maps.size(), firstVisibleIndex + layoutRows * layoutCols);

        for (int index = firstVisibleIndex; index < lastVisibleIndex; index++) {
            int visibleIndex = index - firstVisibleIndex;
            int col = visibleIndex % layoutCols;
            int row = visibleIndex / layoutCols;

            int cardX = startX + col * (cardWidth + cardGap);
            int cardTopY = baseY + row * (cardHeight + cardGap);

            if (mouseX >= cardX && mouseX <= cardX + cardWidth && mouseY >= cardTopY && mouseY <= cardTopY + cardHeight) {
                ClientPlayNetworking.send(new MapVotePayload(index));
                playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount < 0 && scrollRow < maxScrollRow) {
            scrollRow++;
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.8f);
            return true;
        }
        if (verticalAmount > 0 && scrollRow > 0) {
            scrollRow--;
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.8f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void playSound(net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        if (client != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
        }
    }

    private Text parseUnavailableReason(String reason) {
        if ("random_excluded".equals(reason)) {
            return Text.translatable("gui.wathe.map_voting.unavailable.random_excluded");
        }
        if (reason != null && reason.contains(":")) {
            String[] parts = reason.split(":", 2);
            if ("min_players".equals(parts[0])) {
                return Text.translatable("gui.wathe.map_voting.unavailable.min_players", parts[1]);
            }
            if ("max_players".equals(parts[0])) {
                return Text.translatable("gui.wathe.map_voting.unavailable.max_players", parts[1]);
            }
        }
        return Text.translatable("gui.wathe.map_voting.unavailable");
    }

    private void drawCenteredText(DrawContext context, Text text, int centerX, int y, int color) {
        int width = textRenderer.getWidth(text);
        context.drawText(textRenderer, text, centerX - width / 2, y, color, false);
    }

    private void drawCenteredText(DrawContext context, String text, int centerX, int y, int color) {
        int width = textRenderer.getWidth(text);
        context.drawText(textRenderer, text, centerX - width / 2, y, color, false);
    }

    private float easeOutBack(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }
}
