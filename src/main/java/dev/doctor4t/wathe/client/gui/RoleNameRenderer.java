package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.api.client.gui.RoleNameHudApi;
import dev.doctor4t.wathe.api.visibility.TargetVisibilityApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import dev.doctor4t.wathe.entity.NoteEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import org.jetbrains.annotations.NotNull;

public class RoleNameRenderer {
    private static TrainRole targetRole = TrainRole.BYSTANDER;
    private static float nametagAlpha = 0f;
    private static float noteAlpha = 0f;
    private static Text nametag = Text.empty();
    private static final Text[] note = new Text[]{Text.empty(), Text.empty(), Text.empty(), Text.empty()};
    private static PlayerEntity targetPlayer = null;
    private static PlayerEntity displayedTargetPlayer = null;
    private static Entity targetEntity = null;

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, DrawContext context, RenderTickCounter tickCounter) {
        GameWorldComponent component = GameWorldComponent.KEY.get(player.getWorld());
        if (!RoleNameHudApi.shouldRenderHud(player)) {
            nametagAlpha = 0f;
            noteAlpha = 0f;
            targetPlayer = null;
            displayedTargetPlayer = null;
            targetEntity = null;
            return;
        }
        boolean spectatorOrCreative = GameFunctions.isPlayerSpectatingOrCreative(player);
        /*
         * 黑暗环境下仍然隐藏“局内存活玩家”的准心名字，保留停电/夜间玩法的信息限制。
         * 但旁观/创造且没有特殊存活授权的玩家已经不参与本局胜负和推理，
         * 需要能在停电等黑暗环境里继续观察存活玩家名字，方便复盘、导播和管理员查看。
         */
        if (!spectatorOrCreative && player.getWorld().getLightLevel(LightType.BLOCK, BlockPos.ofFloored(player.getEyePos())) < 3 && player.getWorld().getLightLevel(LightType.SKY, BlockPos.ofFloored(player.getEyePos())) < 10)
            return;
        float range = spectatorOrCreative ? 8f : 2f;
        Entity raycastSource = RoleNameHudApi.resolveRaycastSource(player);
        if (ProjectileUtil.getCollision(raycastSource, entity -> {
            if (entity instanceof PlayerEntity target) {
                return TargetVisibilityApi.canTargetPlayer(player, target)
                        && RoleNameHudApi.shouldIncludePlayerTarget(player, target);
            }
            return TargetVisibilityApi.canTargetEntity(player, entity)
                    && RoleNameHudApi.resolveEntityName(player, entity) != null;
        }, range) instanceof EntityHitResult entityHitResult) {
            Entity hitEntity = entityHitResult.getEntity();
            if (hitEntity instanceof PlayerEntity target) {
                targetPlayer = target;
                displayedTargetPlayer = target;
                targetEntity = target;
                nametagAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, nametagAlpha, 1f);
                Text originalName = target.getDisplayName();
                nametag = RoleNameHudApi.resolveName(player, target, originalName);
                /*
                 * 目标侧分两步判断：
                 * 1. countsAsCohort 表示“这个 subject 本身就是双向同伙成员”，例如真杀手、Hacker、
                 *    或明确允许双向识别的 Executioner；
                 * 2. showsAsCohortTarget 只补充“target 在当前 viewer 眼里显示成同伙”的单向伪装，
                 *    例如 Mimic、Jester、Vulture、Dreamer。它只影响目标显示，不会给 viewer 反查资格。
                 */
                boolean targetCountsAsCohort = RoleNameHudApi.countsAsCohort(player, target, component.canUseKillerFeatures(target));
                if (RoleNameHudApi.showsAsCohortTarget(player, target, targetCountsAsCohort)) {
                    targetRole = TrainRole.KILLER;
                } else {
                    targetRole = TrainRole.BYSTANDER;
                }
                boolean shouldObfuscate = PlayerPsychoComponent.KEY.get(target).getPsychoTicks() > 0;
                nametag = shouldObfuscate ? Text.literal("urscrewed" + "X".repeat(player.getRandom().nextInt(8))).styled(style -> style.withFormatting(Formatting.OBFUSCATED, Formatting.DARK_RED)) : nametag;
            } else {
                Text entityName = RoleNameHudApi.resolveEntityName(player, hitEntity);
                if (entityName != null) {
                    /*
                     * 非玩家实体只复用“准心名牌”的淡入淡出和坐标，不参与同伙识别。
                     * 扩展职业例如魔术师播放体只需要告诉 Wathe 这个实体此刻应该显示什么名字，
                     * 不再 mixin 到 RoleNameRenderer 复制整段射线和绘制逻辑。
                     */
                    targetPlayer = null;
                    displayedTargetPlayer = null;
                    targetEntity = hitEntity;
                    targetRole = TrainRole.BYSTANDER;
                    nametagAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, nametagAlpha, 1f);
                    nametag = entityName;
                } else {
                    targetPlayer = null;
                    targetEntity = null;
                    nametagAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, nametagAlpha, 0f);
                    if (nametagAlpha <= 0.05f) {
                        displayedTargetPlayer = null;
                    }
                }
            }
        } else {
            targetPlayer = null;
            targetEntity = null;
            nametagAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, nametagAlpha, 0f);
            if (nametagAlpha <= 0.05f) {
                displayedTargetPlayer = null;
            }
        }
        if (nametagAlpha > 0.05f) {
            context.getMatrices().push();
            context.getMatrices().translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight() / 2f + 6, 0);
            context.getMatrices().scale(0.6f, 0.6f, 1f);
            /*
             * 玩家准心信息现在由 Wathe 统一按固定层级排版：
             * 1. 扩展职业详情；2. 阵营归属；3. 玩家名字；4. 杀手同伙。
             * 过去 HarpyModLoader 会 mixin 到第一次 drawTextWithShadow 前手动画职业名，
             * 不仅依赖调用 ordinal，还无法在中间安全插入阵营行。现在所有固定行都通过
             * RoleNameHudApi 解析，扩展只负责提供文本与 RGB，不再接触矩阵和魔法坐标。
             */
            RoleNameHudApi.RoleNameLine roleDetailsLine = null;
            RoleNameHudApi.RoleNameLine affiliationLine = null;
            if (displayedTargetPlayer != null) {
                var displayedTargetRole = component.getRole(displayedTargetPlayer);
                roleDetailsLine = RoleNameHudApi.resolveRoleDetailsLine(player, displayedTargetPlayer, displayedTargetRole);
                affiliationLine = RoleNameHudApi.resolveAffiliationLine(player, displayedTargetPlayer, displayedTargetRole);
            }

            int nameY = 16;
            if (roleDetailsLine != null) {
                drawCenteredText(renderer, context, roleDetailsLine.text(), 0, roleDetailsLine.color(), nametagAlpha);
            }
            if (affiliationLine != null) {
                /*
                 * 阵营行故意略小于职业名与玩家名，避免三行信息挤压准心；
                 * 若同时存在职业详情，则放到其下一行，并把玩家名只下移必要的少量距离。
                 */
                int affiliationY = roleDetailsLine == null ? 0 : renderer.fontHeight + 2;
                Text formattedAffiliation = RoleNameHudApi.formatAffiliationLine(affiliationLine);
                drawCenteredScaledText(renderer, context, formattedAffiliation, affiliationY, 0.9f,
                        affiliationLine.color(), nametagAlpha);
                if (roleDetailsLine != null) {
                    nameY = affiliationY + Math.round(renderer.fontHeight * 0.8f) + 3;
                }
            }
            int nameWidth = renderer.getWidth(nametag);
            context.drawTextWithShadow(renderer, nametag, -nameWidth / 2, nameY,
                    MathHelper.packRgb(1f, 1f, 1f) | ((int) (nametagAlpha * 255) << 24));
            if (component.isRunning()) {
                TrainRole playerRole = TrainRole.BYSTANDER;
                /*
                 * 自己侧只能使用双向 cohort 状态，绝不能调用 showsAsCohortTarget。
                 * 这样“被杀手看起来像同伙”的单向职业不会因为准心对准真杀手而反向看到
                 * “杀手同伙”提示；Executioner / Hacker 这类明确保留双向机制的职业则仍会通过这里。
                 */
                if (RoleNameHudApi.countsAsCohort(player, player, component.canUseKillerFeatures(player))) playerRole = TrainRole.KILLER;
                if (displayedTargetPlayer != null
                        && playerRole == TrainRole.KILLER
                        && targetRole == TrainRole.KILLER
                        && RoleNameHudApi.shouldShowCohortHint(player, displayedTargetPlayer, true)) {
                    MutableText roleText = Text.translatable("game.tip.cohort");
                    int roleWidth = renderer.getWidth(roleText);
                    int cohortY = nameY + renderer.fontHeight + 4;
                    context.drawTextWithShadow(renderer, roleText, -roleWidth / 2, cohortY,
                            MathHelper.packRgb(1f, 0f, 0f) | ((int) (nametagAlpha * 255) << 24));
                }
            }
            context.getMatrices().pop();
        }
        if (ProjectileUtil.getCollision(raycastSource, entity -> entity instanceof NoteEntity, range) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof NoteEntity note) {
            noteAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, noteAlpha, 1f);
            nametagAlpha = MathHelper.lerp(tickCounter.getTickDelta(true), nametagAlpha, 0f);
            RoleNameRenderer.note[0] = Text.literal(note.getLines()[0]);
            RoleNameRenderer.note[1] = Text.literal(note.getLines()[1]);
            RoleNameRenderer.note[2] = Text.literal(note.getLines()[2]);
            RoleNameRenderer.note[3] = Text.literal(note.getLines()[3]);
        } else {
            noteAlpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4, noteAlpha, 0f);
        }
        if (noteAlpha > 0.05f) {
            context.getMatrices().push();
            context.getMatrices().translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight() / 2f + 6, 0);
            context.getMatrices().scale(0.6f, 0.6f, 1f);
            for (int i = 0; i < note.length; i++) {
                Text line = note[i];
                int lineWidth = renderer.getWidth(line);
                context.drawTextWithShadow(renderer, line, -lineWidth / 2, 16 + (i * (renderer.fontHeight + 2)), MathHelper.packRgb(1f, 1f, 1f) | ((int) (noteAlpha * 255) << 24));
            }
            context.getMatrices().pop();
        }
        /*
         * 扩展职业的准心提示统一放到最后渲染。
         * 这样它们可以复用 Wathe 已经算好的目标玩家、淡入淡出透明度和射线距离，
         * 不需要再 mixin 到 getDisplayName()/getCollision() 这些脆弱调用点。
         */
        RoleNameHudApi.renderExtraHud(new RoleNameHudApi.Context(
                renderer,
                player,
                context,
                tickCounter,
                range,
                targetPlayer,
                targetEntity,
                nametag,
                nametagAlpha,
                noteAlpha
        ));
    }

    public static PlayerEntity getTargetPlayer() {
        return targetPlayer;
    }

    private static void drawCenteredText(TextRenderer renderer,
                                         DrawContext context,
                                         Text text,
                                         int y,
                                         int rgb,
                                         float alpha) {
        int width = renderer.getWidth(text);
        context.drawTextWithShadow(renderer, text, -width / 2, y,
                (rgb & 0x00FFFFFF) | ((int) (alpha * 255) << 24));
    }

    private static void drawCenteredScaledText(TextRenderer renderer,
                                               DrawContext context,
                                               Text text,
                                               int y,
                                               float scale,
                                               int rgb,
                                               float alpha) {
        context.getMatrices().push();
        context.getMatrices().translate(0, y, 0);
        context.getMatrices().scale(scale, scale, 1f);
        drawCenteredText(renderer, context, text, 0, rgb, alpha);
        context.getMatrices().pop();
    }

    private enum TrainRole {
        KILLER,
        BYSTANDER
    }
}
