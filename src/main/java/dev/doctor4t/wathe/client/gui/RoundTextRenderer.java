package dev.doctor4t.wathe.client.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.cca.GameRoundEndComponent;
import dev.doctor4t.wathe.cca.GameRoundEndComponent.RoundEndRoleDisplay;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheSounds;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoundTextRenderer {
    private static final Map<String, Optional<GameProfile>> failCache = new HashMap<>();
    private static final int WELCOME_DURATION = 200 + GameConstants.FADE_TIME * 2;
    private static final int END_DURATION = 200;
    private static RoleAnnouncementTexts.RoleAnnouncementText role = RoleAnnouncementTexts.CIVILIAN;
    private static int welcomeTime = 0;
    private static int killers = 0;
    private static int targets = 0;
    private static int endTime = 0;
    // =========================
    // 结算界面布局常量
    // =========================
    // 平民阵营每行显示 5 个头像。
    private static final int END_GRID_COLUMNS_CIVILIAN = 5;
    // 双列阵营（如警探/杀手）每行显示 3 个头像。
    private static final int END_GRID_COLUMNS_DOUBLE = 3;
    // Loose Ends 模式每行显示 6 个头像。
    private static final int END_GRID_COLUMNS_LOOSE_END = 6;
    // 动态扩列时使用的基础人数。
    // 结算人数不足 6 人时，也按 6 人来判断，不会过早触发扩列。
    private static final int END_DYNAMIC_COLUMNS_BASE_COUNT = 6;
    // 左侧平民 / 中立阵营的扩列步长。
    // 每当人数在基础 6 人之上再增加 8 人，就额外多一列。
    private static final int END_DYNAMIC_COLUMNS_LEFT_STEP = 8;
    // 右侧义警 / 杀手阵营的扩列步长。
    // 每当人数在基础 6 人之上再增加 14 人，就额外多一列。
    private static final int END_DYNAMIC_COLUMNS_RIGHT_STEP = 14;
    // 左侧平民 / 中立阵营的头像起点模式。
    // 1. 小于等于 -1：每一行都从左边开始渲染；
    // 2. 等于 0：每一行都以阵营区域中线为基准居中渲染；
    // 3. 大于等于 1：每一行都从右边开始渲染。
    // 当前默认继续保持左起，这样平民 / 中立人数偏少时不会显得重心偏右。
    private static final int END_LEFT_GROUP_RENDER_DIRECTION_MODE = 0;
    // 右侧义警 / 杀手阵营的头像起点模式。
    // 1. 小于等于 -1：每一行都从左边开始渲染；
    // 2. 等于 0：每一行都以阵营区域中线为基准居中渲染；
    // 3. 大于等于 1：每一行都从右边开始渲染。
    // 当前按你的要求默认设为 0，让义警 / 杀手从中间开始排，更方便后续调试观感。
    private static final int END_RIGHT_GROUP_RENDER_DIRECTION_MODE = 0;

    // 单个“头像 + 名字 + 职业”结算卡片本身占用的可视宽度。
    // 阵营标题居中、分组宽度、首个头像起点都会基于这个宽度动态推算。
    private static final float END_SLOT_CONTENT_WIDTH = 34f;
    // 相邻两个玩家结算单元之间的横向间距。
    // 想让结算界面更紧凑，就调小；想让名字之间更疏一点，就调大。
    private static final float END_SLOT_STEP_X = 30f;
    // 相邻两行玩家结算单元之间的纵向间距。
    // 这个值越小，整体越紧凑；但如果太小，下一行头像可能会压到上一行职业名。
    private static final float END_SLOT_STEP_Y = 32f;

    // 阵营标题（平民/警探/杀手）所在的纵向位置。
    private static final float END_HEADER_Y = 13f;
    // 第一行头像开始绘制的纵向位置。
    private static final float END_GRID_START_Y = 24f;
    // 上一个阵营块结束后，到下一个阵营标题之间额外预留的间距。
    private static final float END_SECTION_GAP_Y = 14f;
    // 额外阵营标题相对于上一组头像区域底部的微调偏移。
    private static final float END_EXTRA_HEADER_OFFSET_Y = 3f;
    // 左右两个大阵营块之间的理想横向间距。
    private static final float END_SECTION_GROUP_GAP_X = 18f;
    // 屏幕变窄时，左右两个阵营块允许缩到的最小横向间距。
    private static final float END_SECTION_GROUP_GAP_X_MIN = 8f;
    // 结算界面左右边缘保留的安全距离，避免头像或名字贴到屏幕边上。
    private static final float END_LAYOUT_SIDE_PADDING = 14f;

    // 单个玩家结算单元里，头像/名字/职业整体居中的横坐标。
    private static final float END_LABEL_CENTER_X = END_SLOT_CONTENT_WIDTH / 2f;
    // 玩家名字文本的纵向位置。
    private static final float END_NAME_Y = 17f;
    // 职业名字文本的纵向位置。
    private static final float END_ROLE_Y = 23f;
    // 名字和职业允许占用的最大文本宽度。
    // 如果名字太长，会自动缩小或省略，避免和相邻玩家重叠。
    private static final int END_LABEL_MAX_WIDTH = 25;
    // 单个玩家结算卡片从顶部到最底部职业文字的大致高度。
    // 它用于估算整个阵营块的总高度，好把整块内容往上提，避免压到物品栏区域。
    private static final float END_SLOT_CONTENT_BOTTOM_Y = 42f;
    // 结算整块内容默认相对于屏幕垂直中心上移多少。
    // 原版布局本来就是“屏幕中心再上移 40 像素”，
    // 这里继续沿用这个思路，这样整体视觉会更接近未改版的结算页。
    private static final float END_ROOT_DEFAULT_OFFSET_Y = 45f;
    // 结算整块内容允许上移到的最顶端安全距离。
    // 当人数非常多时，只允许它继续往上顶到这个位置，避免顶出屏幕。
    private static final float END_ROOT_MIN_TRANSLATE_Y = 14f;
    // 结算整块内容整体向右微调的距离。
    // 当前版本因为左侧平民区更宽，视觉重心会略显偏左，
    // 这里轻微右移一点，让它更接近原版“看起来在正中”的效果。
    private static final float END_ROOT_OFFSET_X = 4f;
    // 结算最底部距离热键栏和底部 HUD 预留的安全距离。
    // 如果你后面还想让整块再往上一点，可以适当调大这个值。
    private static final float END_ROOT_BOTTOM_SAFE_PADDING = 74f;

    // 玩家头像的缩放倍数。
    // 原版皮肤头像是 8x8，这里略微缩小一点，让整体更紧凑，
    // 但仍然保持和下方名字宽度接近，方便辨认。
    private static final float END_HEAD_SCALE = 2.0f;
    // 死亡红叉中心点距离“头像右边缘”向左收多少。
    // 这样头像大小一改，红叉会自动继续贴着头像右上区域，不会留在原来的死坐标上。
    private static final float END_DEATH_MARK_RIGHT_INSET = 2.0f;
    // 死亡红叉距离头像顶部的偏移。
    // 如果你想让红叉更往下压一点，就把这个值调大。
    private static final float END_DEATH_MARK_TOP_OFFSET = 1.6f;
    // 红叉横向缩放相对于头像缩放的倍率。
    // 这里略微放大，让红叉比之前更醒目。
    private static final float END_DEATH_MARK_SCALE_X_MULTIPLIER = 1.25f;
    // 红叉纵向缩放相对于头像缩放的倍率。
    // 保持比横向略扁一些，视觉上更接近以前的“拉宽红叉”效果。
    private static final float END_DEATH_MARK_SCALE_Y_MULTIPLIER = 0.70f;

    // 玩家名字的理想缩放倍数。
    private static final float END_NAME_PREFERRED_SCALE = 0.70f;
    // 职业名字的理想缩放倍数。
    private static final float END_ROLE_PREFERRED_SCALE = 0.60f;
    // 当名字特别长时，允许自动缩小到的最小倍数。
    // 再长就会开始省略，避免挤到别的头像区域。
    private static final float END_MIN_TEXT_SCALE = 0.30f;
    // 当前这一帧 HUD 的屏幕宽度缓存。
    // 阵营标题、首个头像起点、整块布局是否贴边，都会根据它动态计算，
    // 不再依赖固定死坐标。
    private static float endHudWidth = 320f;
    // 当前这一帧 HUD 的屏幕高度缓存。
    // 主要用于限制结算块整体不要压到屏幕下方和物品栏附近。
    private static float endHudHeight = 180f;
    // 当前这一帧结算里各阵营的人数缓存。
    // 这样像 KinsWathe 这类扩展模组在注入同一帧渲染时，
    // 也能拿到已经算好的动态布局结果，而不是只能猜一个固定坐标。
    private static int endCivilianCount = 0;
    private static int endVigilanteCount = 0;
    private static int endKillerCount = 0;
    private static int endLooseEndCount = 0;
    // 左侧额外阵营（例如 KinsWathe 的中立阵营）的人数与列数缓存。
    // 主模组本身不关心额外阵营是谁，但需要知道它会占多高，
    // 才能把整个结算块提前往上挪，避免和物品栏或其他阵营重叠。
    private static int endExternalLeftExtraCount = 0;
    private static int endExternalLeftExtraColumns = END_GRID_COLUMNS_CIVILIAN;

    @SuppressWarnings("IntegerDivisionInFloatingPointContext")
    public static void renderHud(TextRenderer renderer, ClientPlayerEntity player, @NotNull DrawContext context) {
        boolean isLooseEnds = GameWorldComponent.KEY.get(player.getWorld()).getGameMode() == WatheGameModes.LOOSE_ENDS;
        endHudWidth = context.getScaledWindowWidth();
        endHudHeight = context.getScaledWindowHeight();

        if (welcomeTime > 0) {
            context.getMatrices().push();
            context.getMatrices().translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight() / 2f + 3.5, 0);
            context.getMatrices().push();
            context.getMatrices().scale(2.6f, 2.6f, 1f);
            int color = isLooseEnds ? 0x9F0000 : 0xFFFFFF;
            if (welcomeTime <= 180) {
                Text welcomeText = isLooseEnds ? Text.translatable("announcement.loose_ends.welcome") : role.welcomeText;
                context.drawTextWithShadow(renderer, welcomeText, -renderer.getWidth(welcomeText) / 2, -12, color);
            }
            context.getMatrices().pop();
            context.getMatrices().push();
            context.getMatrices().scale(1.2f, 1.2f, 1f);
            if (welcomeTime <= 120) {
                Text premiseText = isLooseEnds ? Text.translatable("announcement.loose_ends.premise") : role.premiseText.apply(killers);
                context.drawTextWithShadow(renderer, premiseText, -renderer.getWidth(premiseText) / 2, 0, color);
            }
            context.getMatrices().pop();
            context.getMatrices().push();
            context.getMatrices().scale(1f, 1f, 1f);
            if (welcomeTime <= 60) {
                Text goalText = isLooseEnds ? Text.translatable("announcement.loose_ends.goal") : role.goalText.apply(targets);
                context.drawTextWithShadow(renderer, goalText, -renderer.getWidth(goalText) / 2, 14, color);
            }
            context.getMatrices().pop();
            context.getMatrices().pop();
        }
        GameWorldComponent game = GameWorldComponent.KEY.get(player.getWorld());
        if (endTime > 0 && endTime < END_DURATION - (GameConstants.FADE_TIME * 2) && !game.isRunning() && game.getGameMode() != WatheGameModes.DISCOVERY) {
            GameRoundEndComponent roundEnd = GameRoundEndComponent.KEY.get(player.getWorld());
            if (roundEnd.getWinStatus() == GameFunctions.WinStatus.NONE) return;
            PlayerEntity winner = player.getWorld().getPlayerByUuid(game.getLooseEndWinner() == null ? UUID.randomUUID() : game.getLooseEndWinner());
            Text endText = role.getEndText(roundEnd.getWinStatus(), winner == null ? Text.empty() : winner.getDisplayName());
            if (endText == null) return;

            int civilianTotal = 0;
            int vigilanteTotal = 0;
            int killerTotal = 0;
            int looseEndTotal = 0;
            if (isLooseEnds) {
                looseEndTotal = roundEnd.getPlayers().size();
            } else {
                for (GameRoundEndComponent.RoundEndData entry : roundEnd.getPlayers()) {
                    if (entry.role() == RoleAnnouncementTexts.VIGILANTE) vigilanteTotal += 1;
                    else if (entry.role() == RoleAnnouncementTexts.CIVILIAN) civilianTotal += 1;
                    else if (entry.role() == RoleAnnouncementTexts.KILLER) killerTotal += 1;
                }
            }

            context.getMatrices().push();
            context.getMatrices().translate(
                    context.getScaledWindowWidth() / 2f + END_ROOT_OFFSET_X,
                    getEndRootTranslateY(isLooseEnds, civilianTotal, vigilanteTotal, killerTotal, looseEndTotal),
                    0
            );
            context.getMatrices().push();
            context.getMatrices().scale(2.6f, 2.6f, 1f);
            context.drawTextWithShadow(renderer, endText, -renderer.getWidth(endText) / 2, -12, 0xFFFFFF);
            context.getMatrices().pop();
            context.getMatrices().push();
            context.getMatrices().scale(1.2f, 1.2f, 1f);
            MutableText winMessage = Text.translatable("game.win." + roundEnd.getWinStatus().name().toLowerCase().toLowerCase());
            context.drawTextWithShadow(renderer, winMessage, -renderer.getWidth(winMessage) / 2, -4, 0xFFFFFF);
            context.getMatrices().pop();
            if (isLooseEnds) {
                endCivilianCount = 0;
                endVigilanteCount = 0;
                endKillerCount = 0;
                endLooseEndCount = looseEndTotal;
                context.drawTextWithShadow(
                        renderer,
                        RoleAnnouncementTexts.LOOSE_END.titleText,
                        (int) (-renderer.getWidth(RoleAnnouncementTexts.LOOSE_END.titleText) / 2f + getLooseEndGroupCenterX()),
                        (int) END_HEADER_Y,
                        0xFFFFFF
                );
                int looseEnds = 0;
                for (GameRoundEndComponent.RoundEndData entry : roundEnd.getPlayers()) {
                    context.getMatrices().push();
                    context.getMatrices().translate(
                            getLooseEndColumnStartX(looseEnds, roundEnd.getPlayers().size()) + (looseEnds % END_GRID_COLUMNS_LOOSE_END) * END_SLOT_STEP_X,
                            getTeamGridStartY() + (looseEnds / END_GRID_COLUMNS_LOOSE_END) * END_SLOT_STEP_Y,
                            0
                    );
                    looseEnds++;
                    renderRoundEndPlayer(renderer, context, roundEnd, entry);
                    context.getMatrices().pop();
                }
                context.getMatrices().pop();
            } else {
                endLooseEndCount = 0;
                endCivilianCount = civilianTotal;
                endVigilanteCount = vigilanteTotal;
                endKillerCount = killerTotal;
                int roundTotalForColumns = getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal);
                int civilianColumns = getEndGridColumnsCivilian(roundTotalForColumns);
                int rightColumns = getEndGridColumnsDouble(roundTotalForColumns);
                context.drawTextWithShadow(
                        renderer,
                        RoleAnnouncementTexts.CIVILIAN.titleText,
                        (int) (-renderer.getWidth(RoleAnnouncementTexts.CIVILIAN.titleText) / 2f + getCivilianGroupCenterX(civilianTotal, vigilanteTotal, killerTotal)),
                        (int) END_HEADER_Y,
                        0xFFFFFF
                );
                context.drawTextWithShadow(
                        renderer,
                        RoleAnnouncementTexts.VIGILANTE.titleText,
                        (int) (-renderer.getWidth(RoleAnnouncementTexts.VIGILANTE.titleText) / 2f + getVigilanteGroupCenterX(civilianTotal, vigilanteTotal, killerTotal)),
                        (int) END_HEADER_Y,
                        0xFFFFFF
                );
                context.drawTextWithShadow(
                        renderer,
                        RoleAnnouncementTexts.KILLER.titleText,
                        (int) (-renderer.getWidth(RoleAnnouncementTexts.KILLER.titleText) / 2f + getKillerGroupCenterX(civilianTotal, vigilanteTotal, killerTotal)),
                        (int) getKillerHeaderY(vigilanteTotal, civilianTotal, killerTotal),
                        0xFFFFFF
                );
                int civilians = 0;
                int vigilantes = 0;
                int killers = 0;
                for (GameRoundEndComponent.RoundEndData entry : roundEnd.getPlayers()) {
                    context.getMatrices().push();
                    if (entry.role() == RoleAnnouncementTexts.CIVILIAN) {
                        context.getMatrices().translate(
                                getCivilianColumnStartX(civilians, civilianTotal, vigilanteTotal, killerTotal) + (civilians % civilianColumns) * END_SLOT_STEP_X,
                                getTeamGridStartY() + (civilians / civilianColumns) * END_SLOT_STEP_Y,
                                0
                        );
                        civilians++;
                    } else if (entry.role() == RoleAnnouncementTexts.VIGILANTE) {
                        context.getMatrices().translate(
                                getVigilanteColumnStartX(vigilantes, civilianTotal, vigilanteTotal, killerTotal) + (vigilantes % rightColumns) * END_SLOT_STEP_X,
                                getTeamGridStartY() + (vigilantes / rightColumns) * END_SLOT_STEP_Y,
                                0
                        );
                        vigilantes++;
                    } else if (entry.role() == RoleAnnouncementTexts.KILLER) {
                        context.getMatrices().translate(
                                getKillerColumnStartX(killers, civilianTotal, vigilanteTotal, killerTotal) + (killers % rightColumns) * END_SLOT_STEP_X,
                                getKillerGridStartY(vigilanteTotal, civilianTotal, killerTotal) + (killers / rightColumns) * END_SLOT_STEP_Y,
                                0
                        );
                        killers++;
                    }

                    renderRoundEndPlayer(renderer, context, roundEnd, entry);
                    context.getMatrices().pop();
                }
                context.getMatrices().pop();
            }
        }
    }

    public static void tick() {
        if (MinecraftClient.getInstance().world != null && GameWorldComponent.KEY.get(MinecraftClient.getInstance().world).getGameMode() != WatheGameModes.DISCOVERY) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (welcomeTime > 0) {
                switch (welcomeTime) {
                    case 200 -> {
                        if (player != null)
                            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.UI_RISER, SoundCategory.MASTER, 10f, 1f, player.getRandom().nextLong());
                    }
                    case 180 -> {
                        if (player != null)
                            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.UI_PIANO, SoundCategory.MASTER, 10f, 1.25f, player.getRandom().nextLong());
                    }
                    case 120 -> {
                        if (player != null)
                            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.UI_PIANO, SoundCategory.MASTER, 10f, 1.5f, player.getRandom().nextLong());
                    }
                    case 60 -> {
                        if (player != null)
                            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.UI_PIANO, SoundCategory.MASTER, 10f, 1.75f, player.getRandom().nextLong());
                    }
                    case 1 -> {
                        if (player != null)
                            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), WatheSounds.UI_PIANO_STINGER, SoundCategory.MASTER, 10f, 1f, player.getRandom().nextLong());
                    }
                }
                welcomeTime--;
            }
            if (endTime > 0) {
                if (endTime == END_DURATION - (GameConstants.FADE_TIME * 2)) {
                    if (player != null)
                        player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), GameRoundEndComponent.KEY.get(player.getWorld()).didWin(player.getUuid()) ? WatheSounds.UI_PIANO_WIN : WatheSounds.UI_PIANO_LOSE, SoundCategory.MASTER, 10f, 1f, player.getRandom().nextLong());
                }
                endTime--;
            }
            GameOptions options = MinecraftClient.getInstance().options;
            if (options != null && options.playerListKey.isPressed()) endTime = Math.max(2, endTime);
        }
    }

    public static void startWelcome(RoleAnnouncementTexts.RoleAnnouncementText role, int killers, int targets) {
        RoundTextRenderer.role = role;
        welcomeTime = WELCOME_DURATION;
        RoundTextRenderer.killers = killers;
        RoundTextRenderer.targets = targets;
    }

    public static void startEnd() {
        welcomeTime = 0;
        endTime = END_DURATION;
    }

    private static void renderRoundEndPlayer(
            @NotNull TextRenderer renderer,
            @NotNull DrawContext context,
            @NotNull GameRoundEndComponent roundEnd,
            @NotNull GameRoundEndComponent.RoundEndData entry
    ) {
        renderRoundEndHead(context, entry);
        if (entry.wasDead()) {
            renderRoundEndDeathMarker(renderer, context);
        }
        renderRoundEndLabels(renderer, context, entry, roundEnd.getRoleDisplay(entry.player().getId()));
    }

    private static void renderRoundEndHead(@NotNull DrawContext context, @NotNull GameRoundEndComponent.RoundEndData entry) {
        PlayerListEntry playerEntry = WatheClient.PLAYER_ENTRIES_CACHE.get(entry.player().getId());
        if (playerEntry == null || playerEntry.getSkinTextures().texture() == null) {
            return;
        }

        Identifier texture = playerEntry.getSkinTextures().texture();
        RenderSystem.enableBlend();
        context.getMatrices().push();
        /*
         * 把头像始终居中到当前玩家结算单元。
         * 这里直接根据头像缩放后的理论宽度反推左上角位置，
         * 后面如果你只改 END_HEAD_SCALE 或 END_LABEL_CENTER_X，
         * 头像仍然会和名字、职业保持绑定，不会错位。
         */
        float scaledHeadSize = 8f * END_HEAD_SCALE;
        context.getMatrices().translate(END_LABEL_CENTER_X - scaledHeadSize / 2f, 0f, 0f);
        context.getMatrices().scale(END_HEAD_SCALE, END_HEAD_SCALE, 1f);
        float offColour = entry.wasDead() ? 0.4f : 1f;
        context.drawTexturedQuad(texture, 0, 8, 0, 8, 0, 8 / 64f, 16 / 64f, 8 / 64f, 16 / 64f, 1f, offColour, offColour, 1f);
        context.getMatrices().translate(-0.5, -0.5, 0);
        context.getMatrices().scale(1.125f, 1.125f, 1f);
        context.drawTexturedQuad(texture, 0, 8, 0, 8, 0, 40 / 64f, 48 / 64f, 8 / 64f, 16 / 64f, 1f, offColour, offColour, 1f);
        context.getMatrices().pop();
    }

    private static void renderRoundEndDeathMarker(@NotNull TextRenderer renderer, @NotNull DrawContext context) {
        /*
         * 红叉不再使用固定死坐标，而是直接根据头像缩放后的实际尺寸来算位置与大小。
         * 这样后面如果你继续调 END_HEAD_SCALE，红叉会自动一起跟着移动和缩放。
         */
        float scaledHeadSize = 8f * END_HEAD_SCALE;
        float headLeftX = END_LABEL_CENTER_X - scaledHeadSize / 2f;
        float deathMarkX = headLeftX + scaledHeadSize - END_DEATH_MARK_RIGHT_INSET;
        float deathMarkY = END_DEATH_MARK_TOP_OFFSET;
        float deathMarkScaleX = END_HEAD_SCALE * END_DEATH_MARK_SCALE_X_MULTIPLIER;
        float deathMarkScaleY = END_HEAD_SCALE * END_DEATH_MARK_SCALE_Y_MULTIPLIER;

        context.getMatrices().push();
        context.getMatrices().translate(deathMarkX, deathMarkY, 0f);
        context.getMatrices().scale(deathMarkScaleX, deathMarkScaleY, 1f);
        context.drawText(renderer, "x", -renderer.getWidth("x") / 2, 0, 0xE10000, false);
        context.drawText(renderer, "x", -renderer.getWidth("x") / 2, 1, 0x550000, false);
        context.getMatrices().pop();
    }

    private static void renderRoundEndLabels(
            @NotNull TextRenderer renderer,
            @NotNull DrawContext context,
            @NotNull GameRoundEndComponent.RoundEndData entry,
            @NotNull RoundEndRoleDisplay roleDisplay
    ) {
        /*
         * 当前矩阵已经被我们统一成“单个结算格子”的局部坐标，
         * 后续名字/职业文字都直接按像素坐标绘制，
         * 同时仍然绑定在头像所在的同一个矩阵原点上，不会因为头像位移而错位。
         */
        context.getMatrices().push();

        drawFittedCenteredText(
                renderer,
                context,
                Text.literal(entry.player().getName()),
                END_LABEL_CENTER_X,
                END_NAME_Y,
                entry.wasDead() ? 0xAFAFAF : 0xFFFFFF,
                END_LABEL_MAX_WIDTH,
                END_NAME_PREFERRED_SCALE
        );

        Text roleText = getRoleDisplayText(roleDisplay);
        if (!roleText.getString().isEmpty()) {
            drawFittedCenteredText(
                    renderer,
                    context,
                    roleText,
                    END_LABEL_CENTER_X,
                    END_ROLE_Y,
                    multiplyColour(roleDisplay.color(), entry.wasDead() ? 0.7f : 1f),
                    END_LABEL_MAX_WIDTH,
                    END_ROLE_PREFERRED_SCALE
            );
        }

        context.getMatrices().pop();
    }

    public static int getEndGridColumnsCivilian() {
        return getEndGridColumnsCivilian(getCurrentEndRoundTotalCount());
    }

    /**
     * 根据“服务器总结算人数”动态计算左侧阵营每行显示多少头像。
     * 规则：
     * 1. 先以 6 人作为最小基底；
     * 2. 在 6 人基础上每多 12 人，就额外多一列；
     * 3. 例如 6~17 人时仍是基础列数，18~29 人时多 1 列。
     * 这里的 total 应当传“本局结算的服务器总人数”，
     * 包括平民、义警、杀手，以及像 KinsWathe 中立这类额外阵营。
     */
    public static int getEndGridColumnsCivilian(int total) {
        return getDynamicColumns(total, END_GRID_COLUMNS_CIVILIAN, END_DYNAMIC_COLUMNS_LEFT_STEP);
    }

    /**
     * 根据“服务器总结算人数”动态计算右侧阵营每行显示多少头像。
     * 规则：
     * 1. 先以 6 人作为最小基底；
     * 2. 在 6 人基础上每多 18 人，就额外多一列；
     * 3. 例如 6~23 人时仍是基础列数，24~41 人时多 1 列。
     * 这里的 total 同样应当传“本局结算的服务器总人数”。
     */
    public static int getEndGridColumnsDouble(int total) {
        return getDynamicColumns(total, END_GRID_COLUMNS_DOUBLE, END_DYNAMIC_COLUMNS_RIGHT_STEP);
    }

    public static float getEndSlotStepX() {
        return END_SLOT_STEP_X;
    }

    public static float getEndSlotStepY() {
        return END_SLOT_STEP_Y;
    }

    public static float getEndHeaderY() {
        return END_HEADER_Y;
    }

    public static float getTeamGridStartY() {
        return END_GRID_START_Y;
    }

    public static float getCivilianColumnStartX() {
        return getCivilianColumnStartX(0, endCivilianCount, endVigilanteCount, endKillerCount);
    }

    public static float getRightColumnStartX() {
        return getVigilanteColumnStartX(0, endCivilianCount, endVigilanteCount, endKillerCount);
    }

    public static float getLooseEndColumnStartX() {
        return getLooseEndColumnStartX(0, endLooseEndCount);
    }

    public static float getKillerHeaderY(int vigilanteTotal) {
        return getKillerHeaderY(vigilanteTotal, endCivilianCount, endKillerCount);
    }

    public static float getKillerGridStartY(int vigilanteTotal) {
        return getKillerGridStartY(vigilanteTotal, endCivilianCount, endKillerCount);
    }

    public static float getExtraSectionHeaderY(int previousCount, int columns) {
        return getTeamGridStartY() + getRowsForCount(previousCount, columns) * END_SLOT_STEP_Y + END_EXTRA_HEADER_OFFSET_Y;
    }

    public static float getExtraSectionGridStartY(int previousCount, int columns) {
        return getExtraSectionHeaderY(previousCount, columns) + END_SECTION_GAP_Y;
    }

    /**
     * 供扩展模组在只知道“左侧阵营人数”时复用的动态额外阵营标题高度。
     * 这里内部会自动套用“按服务器总人数扩列”的左侧阵营规则。
     */
    public static float getExtraSectionHeaderYForCivilian(int previousCount) {
        return getExtraSectionHeaderY(previousCount, getEndGridColumnsCivilian(getCurrentEndRoundTotalCount()));
    }

    /**
     * 供扩展模组在只知道“左侧阵营人数”时复用的动态额外阵营头像起始高度。
     */
    public static float getExtraSectionGridStartYForCivilian(int previousCount) {
        return getExtraSectionGridStartY(previousCount, getEndGridColumnsCivilian(getCurrentEndRoundTotalCount()));
    }

    public static int getRowsForCount(int count, int columns) {
        if (count <= 0) {
            return 0;
        }
        return (count + columns - 1) / columns;
    }

    /**
     * 供扩展模组登记“左侧额外阵营”的布局信息。
     * 例如 KinsWathe 会把中立阵营的人数和列数告诉主渲染器，
     * 这样主渲染器在决定整块结算区域要往上挪多少时，就能把中立阵营也一起算进去。
     */
    public static void setExternalLeftExtraSection(int count, int columns) {
        endExternalLeftExtraCount = Math.max(0, count);
        endExternalLeftExtraColumns = Math.max(1, columns);
    }

    /**
     * 清理左侧额外阵营布局信息，避免上一局的额外阵营高度串到下一局。
     */
    public static void clearExternalLeftExtraSection() {
        endExternalLeftExtraCount = 0;
        endExternalLeftExtraColumns = END_GRID_COLUMNS_CIVILIAN;
    }

    /**
     * 计算结算块整体应该摆在屏幕什么高度。
     * 核心目标：
     * 1. 默认保持一个稳定、偏上的根坐标，不再随着屏幕尺寸往下漂；
     * 2. 只有在人数很多、内容真的快压到底部时，才继续往上挪；
     * 3. 这样既能保留布局秩序，也能避免把后续 HUD 文本一起“视觉上拖下去”。
     */
    private static float getEndRootTranslateY(boolean isLooseEnds, int civilianTotal, int vigilanteTotal, int killerTotal, int looseEndTotal) {
        float preferredY = endHudHeight / 2f - END_ROOT_DEFAULT_OFFSET_Y;
        float contentBottomY = isLooseEnds
                ? getLooseEndSectionBottomY(looseEndTotal)
                : Math.max(
                        getLeftCombinedSectionBottomY(civilianTotal, vigilanteTotal, killerTotal),
                        getRightCombinedSectionBottomY(civilianTotal, vigilanteTotal, killerTotal)
                );
        float maxAllowedY = endHudHeight - END_ROOT_BOTTOM_SAFE_PADDING - contentBottomY;
        return Math.max(END_ROOT_MIN_TRANSLATE_Y, Math.min(preferredY, maxAllowedY));
    }

    /**
     * 计算一整个阵营块到底会占到多低。
     * 只要这个值算准，整块结算内容就能在渲染前提前上移，避免后面的阵营压到底部。
     */
    private static float getSectionBottomY(int total, int columns, float gridStartY) {
        int rows = getRowsForCount(total, columns);
        if (rows <= 0) {
            return END_HEADER_Y + 10f;
        }
        return gridStartY + (rows - 1) * END_SLOT_STEP_Y + END_SLOT_CONTENT_BOTTOM_Y;
    }

    /**
     * 左侧平民 + 左侧额外阵营（如中立阵营）合并后的最低点。
     */
    private static float getLeftCombinedSectionBottomY(int civilianTotal, int vigilanteTotal, int killerTotal) {
        int roundTotal = getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal);
        float civilianBottom = getSectionBottomY(civilianTotal, getEndGridColumnsCivilian(roundTotal), getTeamGridStartY());
        if (endExternalLeftExtraCount <= 0) {
            return civilianBottom;
        }
        return Math.max(
                civilianBottom,
                getSectionBottomY(
                        endExternalLeftExtraCount,
                        endExternalLeftExtraColumns,
                        getExtraSectionGridStartY(civilianTotal, getEndGridColumnsCivilian(roundTotal))
                )
        );
    }

    /**
     * 右侧义警 + 杀手堆叠后的最低点。
     */
    private static float getRightCombinedSectionBottomY(int civilianTotal, int vigilanteTotal, int killerTotal) {
        int roundTotal = getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal);
        float vigilanteBottom = getSectionBottomY(vigilanteTotal, getEndGridColumnsDouble(roundTotal), getTeamGridStartY());
        float killerBottom = getSectionBottomY(killerTotal, getEndGridColumnsDouble(roundTotal), getKillerGridStartY(vigilanteTotal, civilianTotal, killerTotal));
        return Math.max(vigilanteBottom, killerBottom);
    }

    /**
     * Loose Ends 模式整块的最低点。
     */
    private static float getLooseEndSectionBottomY(int total) {
        return getSectionBottomY(total, END_GRID_COLUMNS_LOOSE_END, getTeamGridStartY());
    }

    /**
     * 获取某个阵营分组的理论宽度。
     * 这里用“内容宽度”和“步进宽度”分开计算，
     * 让最后一列头像右侧不会额外多算一段空白间距。
     */
    private static float getGroupWidth(int columns) {
        if (columns <= 0) {
            return END_SLOT_CONTENT_WIDTH;
        }
        return END_SLOT_CONTENT_WIDTH + Math.max(0, columns - 1) * END_SLOT_STEP_X;
    }

    /**
     * 计算左右两个大阵营块之间的真实间距。
     * 当屏幕足够宽时使用理想间距，屏幕较窄时会自动压缩，
     * 但不会小于最小安全间距，也不会把阵营块挤出屏幕。
     */
    private static float getResolvedGroupGapX() {
        float leftWidth = getConfiguredCivilianGroupWidth();
        float rightWidth = getConfiguredRightGroupWidth();
        float maxAvailable = endHudWidth - END_LAYOUT_SIDE_PADDING * 2f - leftWidth - rightWidth;
        return Math.min(END_SECTION_GROUP_GAP_X, Math.max(0f, maxAvailable));
    }

    /**
     * 计算左侧阵营区域最左边的位置。
     * 这里采用“稳定的大区块”思路：
     * 1. 左侧给平民预留固定最大宽度；
     * 2. 右侧给义警/杀手预留固定最大宽度；
     * 3. 真正的人数变化只影响区块内部的居中，不会让整个左右结构每局都漂移。
     */
    private static float getLeftGroupAreaStartX() {
        float leftWidth = getConfiguredCivilianGroupWidth();
        float rightWidth = getConfiguredRightGroupWidth();
        float totalWidth = leftWidth + getResolvedGroupGapX() + rightWidth;
        float centeredStart = -totalWidth / 2f;
        float minStart = -endHudWidth / 2f + END_LAYOUT_SIDE_PADDING;
        float maxStart = endHudWidth / 2f - END_LAYOUT_SIDE_PADDING - leftWidth;
        return clamp(centeredStart, minStart, maxStart);
    }

    /**
     * 计算右侧阵营区域最左边的位置。
     */
    private static float getRightGroupAreaStartX() {
        return getLeftGroupAreaStartX()
                + getConfiguredCivilianGroupWidth()
                + getResolvedGroupGapX();
    }

    /**
     * 把当前实际阵营宽度居中到一个稳定的“阵营区域”里。
     * 这样阵营内部人数多少可以变化，但左右两大块的秩序感不会乱掉。
     */
    private static float getCenteredGroupStartX(float areaStartX, float areaWidth, float currentGroupWidth) {
        return areaStartX + Math.max(0f, (areaWidth - currentGroupWidth) / 2f);
    }

    /**
     * 根据配置的“左 / 中 / 右起点模式”，
     * 计算一个分组在自己所属区域里的真实起始横坐标。
     */
    private static float getAlignedGroupStartX(float areaStartX, float areaWidth, float currentGroupWidth, int renderDirectionMode) {
        if (renderDirectionMode <= -1) {
            return areaStartX;
        }
        if (renderDirectionMode == 0) {
            return getCenteredGroupStartX(areaStartX, areaWidth, currentGroupWidth);
        }
        return areaStartX + Math.max(0f, areaWidth - currentGroupWidth);
    }

    /**
     * 根据每一行实际会用到的头像数量，
     * 决定这一行应该按左对齐、居中还是右对齐摆放。
     * 这样无论最后一行是不是残缺行，都会严格遵守当前阵营的起点模式常量。
     */
    private static float getAlignedRowStartX(int index, int total, int columns, float areaStartX, float areaWidth, int renderDirectionMode) {
        if (columns <= 0) {
            return areaStartX;
        }
        int currentRow = Math.max(0, index / columns);
        int totalRows = getRowsForCount(total, columns);
        if (totalRows <= 0) {
            return areaStartX;
        }
        int remainder = total % columns;
        boolean isLastRow = currentRow == totalRows - 1;
        int rowColumns = columns;
        if (isLastRow && remainder > 0) {
            rowColumns = remainder;
        }
        return getAlignedGroupStartX(areaStartX, areaWidth, getGroupWidth(rowColumns), renderDirectionMode);
    }

    /**
     * 获取左侧平民阵营标题的中心点横坐标。
     * 标题会绑定到当前平民阵营实际占用宽度的中心点，
     * 并同时遵守左侧阵营的起点模式常量。
     */
    public static float getCivilianGroupCenterX(int civilianTotal, int vigilanteTotal, int killerTotal) {
        float currentWidth = getCivilianGroupWidth(civilianTotal, vigilanteTotal, killerTotal);
        float startX = getAlignedGroupStartX(
                getLeftGroupAreaStartX(),
                getConfiguredCivilianGroupWidth(),
                currentWidth,
                END_LEFT_GROUP_RENDER_DIRECTION_MODE
        );
        return startX + currentWidth / 2f;
    }

    /**
     * 暴露给扩展模组使用的“当前帧平民分组中心点”。
     * 适合给 KinsWathe 的中立阵营标题直接复用，让标题稳定显示在所属阵营正上方。
     */
    public static float getCivilianGroupCenterX() {
        return getCivilianGroupCenterX(endCivilianCount, endVigilanteCount, endKillerCount);
    }

    /**
     * 获取右侧义警阵营标题的中心点横坐标。
     */
    private static float getVigilanteGroupCenterX(int civilianTotal, int vigilanteTotal, int killerTotal) {
        int columns = getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal));
        float vigilanteWidth = getGroupWidth(getUsedColumns(vigilanteTotal, columns));
        float startX = getAlignedGroupStartX(
                getRightGroupAreaStartX(),
                getConfiguredRightGroupWidth(),
                vigilanteWidth,
                END_RIGHT_GROUP_RENDER_DIRECTION_MODE
        );
        return startX + vigilanteWidth / 2f;
    }

    /**
     * 获取右侧杀手阵营标题的中心点横坐标。
     * 因为杀手与义警共用同一个右侧列宽，所以中心点相同。
     */
    private static float getKillerGroupCenterX(int civilianTotal, int vigilanteTotal, int killerTotal) {
        int columns = getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal));
        float killerWidth = getGroupWidth(getUsedColumns(killerTotal, columns));
        float startX = getAlignedGroupStartX(
                getRightGroupAreaStartX(),
                getConfiguredRightGroupWidth(),
                killerWidth,
                END_RIGHT_GROUP_RENDER_DIRECTION_MODE
        );
        return startX + killerWidth / 2f;
    }

    /**
     * Loose Ends 模式整组头像的中心点横坐标。
     */
    private static float getLooseEndGroupCenterX() {
        return getLooseEndColumnStartX(0, endLooseEndCount) + getLooseEndGroupWidth(endLooseEndCount) / 2f;
    }

    /**
     * 获取平民阵营某一行当前实际应该从哪里开始画。
     * 这里会根据左侧起点模式常量自动切换成左对齐、居中或右对齐。
     */
    public static float getCivilianColumnStartX(int index, int civilianTotal, int vigilanteTotal, int killerTotal) {
        int columns = getEndGridColumnsCivilian(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal));
        return getAlignedRowStartX(
                index,
                civilianTotal,
                columns,
                getLeftGroupAreaStartX(),
                getConfiguredCivilianGroupWidth(),
                END_LEFT_GROUP_RENDER_DIRECTION_MODE
        );
    }

    /**
     * 获取义警阵营某一行当前实际应该从哪里开始画。
     */
    public static float getVigilanteColumnStartX(int index, int civilianTotal, int vigilanteTotal, int killerTotal) {
        int columns = getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal));
        return getAlignedRowStartX(
                index,
                vigilanteTotal,
                columns,
                getRightGroupAreaStartX(),
                getConfiguredRightGroupWidth(),
                END_RIGHT_GROUP_RENDER_DIRECTION_MODE
        );
    }

    /**
     * 获取杀手阵营某一行当前实际应该从哪里开始画。
     */
    public static float getKillerColumnStartX(int index, int civilianTotal, int vigilanteTotal, int killerTotal) {
        int columns = getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal));
        return getAlignedRowStartX(
                index,
                killerTotal,
                columns,
                getRightGroupAreaStartX(),
                getConfiguredRightGroupWidth(),
                END_RIGHT_GROUP_RENDER_DIRECTION_MODE
        );
    }

    /**
     * 获取 Loose Ends 模式某一行的起始横坐标。
     */
    public static float getLooseEndColumnStartX(int index, int total) {
        float groupWidth = getLooseEndGroupWidth(total);
        float baseStart = clamp(
                -groupWidth / 2f,
                -endHudWidth / 2f + END_LAYOUT_SIDE_PADDING,
                endHudWidth / 2f - END_LAYOUT_SIDE_PADDING - groupWidth
        );
        return getRowAwareColumnStartX(index, total, END_GRID_COLUMNS_LOOSE_END, baseStart);
    }

    /**
     * 给“挂在平民列下面的额外阵营”使用的动态起点。
     * 例如 KinsWathe 的中立阵营可以复用这里。
     * 它会和左侧平民阵营使用同一套“左 / 中 / 右起点模式”。
     */
    public static float getCivilianExtraSectionColumnStartX(int index, int total, int columns) {
        return getCivilianExtraSectionColumnStartX(index, total, columns, endCivilianCount, endVigilanteCount, endKillerCount);
    }

    /**
     * 给扩展模组使用的“左侧额外阵营当前行起点”。
     * 传入完整人数后，就算扩展在主渲染统计人数之前插入，也能算出正确位置。
     * 额外阵营会和左侧平民共享同样的起点模式，避免标题与头像对不齐。
     */
    public static float getCivilianExtraSectionColumnStartX(int index, int total, int columns, int civilianTotal, int vigilanteTotal, int killerTotal) {
        return getAlignedRowStartX(
                index,
                total,
                columns,
                getLeftGroupAreaStartX(),
                getConfiguredCivilianGroupWidth(),
                END_LEFT_GROUP_RENDER_DIRECTION_MODE
        );
    }

    /**
     * 给扩展模组使用的“左侧额外阵营标题中心点”。
     * 例如 KinsWathe 的中立阵营标题可以直接复用它，保证标题在本阵营头像正上方。
     * 同时它也会跟随左侧阵营的起点模式常量一起变化。
     */
    public static float getCivilianExtraSectionGroupCenterX(int total, int columns, int civilianTotal, int vigilanteTotal, int killerTotal) {
        float extraWidth = getGroupWidth(getUsedColumns(total, columns));
        float startX = getAlignedGroupStartX(
                getLeftGroupAreaStartX(),
                getConfiguredCivilianGroupWidth(),
                extraWidth,
                END_LEFT_GROUP_RENDER_DIRECTION_MODE
        );
        return startX + extraWidth / 2f;
    }

    /**
     * 根据实际人数计算“这一组现在真正会占几列”。
     * 这样人数很少时，阵营宽度会自动收缩，不会还按满列的宽度来摆标题和整块布局。
     */
    private static int getUsedColumns(int total, int maxColumns) {
        if (maxColumns <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(maxColumns, Math.max(0, total)));
    }

    /**
     * 左侧平民阵营当前实际占用宽度。
     */
    private static float getCivilianGroupWidth(int civilianTotal, int vigilanteTotal, int killerTotal) {
        return getGroupWidth(
                getUsedColumns(
                        civilianTotal,
                        getEndGridColumnsCivilian(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal))
                )
        );
    }

    /**
     * 左侧平民区域按当前配置允许的最大宽度。
     * 它只和“配置列数”相关，不随本局人数漂移。
     */
    private static float getConfiguredCivilianGroupWidth() {
        int columns = getEndGridColumnsCivilian(getCurrentEndRoundTotalCount());
        return getGroupWidth(columns);
    }

    /**
     * 右侧义警/杀手共用列当前实际占用宽度。
     * 因为这两个阵营上下堆叠但列宽要一致，所以取两者中更宽的那个。
     */
    private static float getRightStackGroupWidth(int vigilanteTotal, int killerTotal) {
        int dynamicColumns = getEndGridColumnsDouble(getCurrentEndRoundTotalCount());
        int usedColumns = Math.max(
                getUsedColumns(vigilanteTotal, dynamicColumns),
                getUsedColumns(killerTotal, dynamicColumns)
        );
        return getGroupWidth(usedColumns);
    }

    /**
     * 右侧义警/杀手区域按当前配置允许的最大宽度。
     * 这样右侧整体位置不会因为本局只有 1 个杀手或 2 个义警就左右乱飘。
     */
    private static float getConfiguredRightGroupWidth() {
        int columns = getEndGridColumnsDouble(getCurrentEndRoundTotalCount());
        return getGroupWidth(columns);
    }

    /**
     * Loose Ends 当前实际占用宽度。
     */
    private static float getLooseEndGroupWidth(int total) {
        return getGroupWidth(getUsedColumns(total, END_GRID_COLUMNS_LOOSE_END));
    }

    /**
     * Loose Ends 仍然沿用“最后一行自动居中”的旧逻辑。
     * 因为它只有一个大阵营，整体居中观感会更自然，
     * 所以这里保留这个专用辅助方法，不跟左右阵营的起点模式联动。
     */
    private static float getRowAwareColumnStartX(int index, int total, int columns, float baseStartX) {
        if (columns <= 0) {
            return baseStartX;
        }
        int currentRow = Math.max(0, index / columns);
        int totalRows = getRowsForCount(total, columns);
        if (totalRows == 0) {
            return baseStartX;
        }
        int remainder = total % columns;
        boolean isLastRow = currentRow == totalRows - 1;
        if (isLastRow && remainder > 0 && remainder < columns) {
            float fullWidth = getGroupWidth(columns);
            float lastRowWidth = getGroupWidth(remainder);
            return baseStartX + (fullWidth - lastRowWidth) / 2f;
        }
        return baseStartX;
    }

    /**
     * 计算杀手阵营标题的纵向位置。
     * 会基于义警实际占了多少行来决定，不会因为你改列数后仍然停留在旧的固定高度。
     */
    public static float getKillerHeaderY(int vigilanteTotal, int civilianTotal, int killerTotal) {
        return getExtraSectionHeaderY(vigilanteTotal, getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal)));
    }

    /**
     * 计算杀手头像区域的起始纵向位置。
     */
    public static float getKillerGridStartY(int vigilanteTotal, int civilianTotal, int killerTotal) {
        return getExtraSectionGridStartY(vigilanteTotal, getEndGridColumnsDouble(getEndRoundTotalCount(civilianTotal, vigilanteTotal, killerTotal)));
    }

    /**
     * 动态扩列的通用计算。
     * 例如：
     * 1. 左侧阵营基础列数为 4，基础人数为 6，每多 12 人再加 1 列；
     * 2. 右侧阵营基础列数为 2，基础人数为 6，每多 18 人再加 1 列。
     */
    private static int getDynamicColumns(int total, int baseColumns, int step) {
        if (baseColumns <= 0) {
            baseColumns = 1;
        }
        if (step <= 0) {
            return baseColumns;
        }
        int effectiveTotal = Math.max(total, END_DYNAMIC_COLUMNS_BASE_COUNT);
        int extraColumns = Math.max(0, (effectiveTotal - END_DYNAMIC_COLUMNS_BASE_COUNT) / step);
        return baseColumns + extraColumns;
    }

    /**
     * 获取当前这一帧“结算界面总玩家数”。
     * 这里会把主模组原生阵营和左侧额外阵营一起算进去，
     * 让动态扩列真正按照“服务器总人数”来触发，而不是按单独某个阵营触发。
     */
    private static int getCurrentEndRoundTotalCount() {
        return getEndRoundTotalCount(endCivilianCount, endVigilanteCount, endKillerCount);
    }

    /**
     * 根据本次结算里已知的平民 / 义警 / 杀手数量，
     * 再加上扩展模组登记进来的左侧额外阵营人数，
     * 统一得出本局结算的总人数。
     */
    private static int getEndRoundTotalCount(int civilianTotal, int vigilanteTotal, int killerTotal) {
        return Math.max(0, civilianTotal)
                + Math.max(0, vigilanteTotal)
                + Math.max(0, killerTotal)
                + Math.max(0, endExternalLeftExtraCount);
    }

    /**
     * 数值夹取工具，避免动态布局把内容推到屏幕安全区之外。
     */
    private static float clamp(float value, float min, float max) {
        if (min > max) {
            return (min + max) / 2f;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static void drawFittedCenteredText(
            @NotNull TextRenderer renderer,
            @NotNull DrawContext context,
            @NotNull Text text,
            float centerX,
            float y,
            int color,
            int maxWidth,
            float preferredScale
    ) {
        if (maxWidth <= 0 || text.getString().isEmpty()) {
            return;
        }

        Text fittedText = text;
        int width = renderer.getWidth(fittedText);
        if (width <= 0) {
            return;
        }

        float scale = Math.min(preferredScale, maxWidth / (float) width);
        if (scale < END_MIN_TEXT_SCALE) {
            scale = END_MIN_TEXT_SCALE;
            int unscaledMaxWidth = Math.max(1, Math.round(maxWidth / scale));
            fittedText = Text.literal(trimToWidthWithEllipsis(renderer, fittedText.getString(), unscaledMaxWidth));
            width = renderer.getWidth(fittedText);
            if (width <= 0) {
                return;
            }
        }

        context.getMatrices().push();
        context.getMatrices().translate(centerX, y, 0f);
        context.getMatrices().scale(scale, scale, 1f);
        context.drawTextWithShadow(renderer, fittedText, -width / 2, 0, color);
        context.getMatrices().pop();
    }

    private static @NotNull Text getRoleDisplayText(@NotNull RoundEndRoleDisplay roleDisplay) {
        if (!roleDisplay.translationKey().isEmpty() && Language.getInstance().hasTranslation(roleDisplay.translationKey())) {
            return Text.translatable(roleDisplay.translationKey());
        }
        if (!roleDisplay.fallbackName().isEmpty()) {
            return Text.literal(roleDisplay.fallbackName());
        }
        if (!roleDisplay.translationKey().isEmpty()) {
            return Text.translatable(roleDisplay.translationKey());
        }
        return Text.empty();
    }

    private static @NotNull String trimToWidthWithEllipsis(@NotNull TextRenderer renderer, @NotNull String text, int maxWidth) {
        if (renderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        if (renderer.getWidth(ellipsis) >= maxWidth) {
            return "";
        }

        int end = text.length();
        while (end > 0 && renderer.getWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return end <= 0 ? "" : text.substring(0, end) + ellipsis;
    }

    private static int multiplyColour(int colour, float brightness) {
        int red = Math.min(255, Math.max(0, Math.round(((colour >> 16) & 0xFF) * brightness)));
        int green = Math.min(255, Math.max(0, Math.round(((colour >> 8) & 0xFF) * brightness)));
        int blue = Math.min(255, Math.max(0, Math.round((colour & 0xFF) * brightness)));
        return (red << 16) | (green << 8) | blue;
    }

    public static GameProfile getGameProfile(String disguise) {
        Optional<GameProfile> optional = SkullBlockEntity.fetchProfileByName(disguise).getNow(failCache(disguise));
        return optional.orElse(failCache(disguise).get());
    }

    public static SkinTextures getSkinTextures(String disguise) {
        return MinecraftClient.getInstance().getSkinProvider().getSkinTextures(getGameProfile(disguise));
    }

    public static Optional<GameProfile> failCache(String name) {
        return failCache.computeIfAbsent(name, (d) -> Optional.of(new GameProfile(UUID.randomUUID(), name)));
    }

}
