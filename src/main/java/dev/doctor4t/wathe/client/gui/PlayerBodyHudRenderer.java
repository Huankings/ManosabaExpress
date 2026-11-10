package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.PlayerLifeStateApi;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.api.client.gui.PlayerBodyHudApi;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Wathe 尸体准心 HUD 的唯一绘制器。
 *
 * <p>固定行先根据规则筛选，再连续排版；扩展不再自行写 y 坐标，因此医师、验尸官、
 * 秃鹫、盗墓者和后续职业的行增减不会互相重叠。</p>
 */
public final class PlayerBodyHudRenderer {
    private static float alpha;

    private PlayerBodyHudRenderer() {
    }

    public static void renderHud(@NotNull TextRenderer renderer,
                                 @NotNull ClientPlayerEntity viewer,
                                 @NotNull DrawContext drawContext,
                                 @NotNull RenderTickCounter tickCounter) {
        float range = GameFunctions.isPlayerSpectatingOrCreative(viewer) ? 8.0F : 3.0F;
        PlayerBodyEntity body = PlayerBodyHudApi.findLookedAtBody(viewer, range);
        if (body == null) {
            alpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4.0F, alpha, 0.0F);
            return;
        }

        alpha = MathHelper.lerp(tickCounter.getTickDelta(true) / 4.0F, alpha, 1.0F);
        PlayerBodyHudApi.Context apiContext = new PlayerBodyHudApi.Context(viewer, body);
        List<RenderedLine> lines = new ArrayList<>();

        for (PlayerBodyHudApi.BodyHudLine line : PlayerBodyHudApi.BodyHudLine.values()) {
            PlayerBodyHudApi.BodyHudDecision decision = PlayerBodyHudApi.resolveVisibility(apiContext, line);
            if (decision == PlayerBodyHudApi.BodyHudDecision.HIDE || decision == PlayerBodyHudApi.BodyHudDecision.PASS) {
                continue;
            }
            Text text = PlayerBodyHudApi.resolveLine(apiContext, line);
            if (text == null) {
                text = defaultLine(viewer, body, line);
            }
            if (text == null) {
                continue;
            }
            if (decision == PlayerBodyHudApi.BodyHudDecision.OBFUSCATE) {
                text = obfuscate(text, body, line);
            }
            lines.add(new RenderedLine(text, line == PlayerBodyHudApi.BodyHudLine.DEATH ? Colors.RED : Colors.WHITE));
        }

        List<Text> additionalLines = PlayerBodyHudApi.resolveAdditionalLines(apiContext);
        int y = 16;
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(drawContext.getScaledWindowWidth() / 2.0F,
                drawContext.getScaledWindowHeight() / 2.0F + 6.0F, 0.0F);
        drawContext.getMatrices().scale(0.6F, 0.6F, 1.0F);
        for (RenderedLine line : lines) {
            drawCentered(renderer, drawContext, line.text(), y, line.fallbackColor());
            y += renderer.fontHeight + 2;
        }
        if (!additionalLines.isEmpty() && !lines.isEmpty()) {
            y += 2;
        }
        for (Text line : additionalLines) {
            drawCentered(renderer, drawContext, line, y, Colors.WHITE);
            y += renderer.fontHeight + 2;
        }
        drawContext.getMatrices().pop();
    }

    private static Text defaultLine(@NotNull ClientPlayerEntity viewer,
                                    @NotNull PlayerBodyEntity body,
                                    @NotNull PlayerBodyHudApi.BodyHudLine line) {
        return switch (line) {
            case NAME -> body.getPlayerNameSnapshot().isBlank()
                    ? resolveFallbackName(viewer, body.getPlayerUuid())
                    : Text.literal(body.getPlayerNameSnapshot()).withColor(Colors.WHITE);
            case DEATH -> Text.translatable("hud.wathe.body.death_info",
                    body.getDeathElapsedTicks() / 20,
                    Text.translatable(deathReasonKey(body.getDeathReasonId()))).withColor(Colors.RED);
            case IDENTITY -> defaultIdentity(body);
            case AFFILIATION -> defaultAffiliation(body);
            case KILLER -> defaultKiller(viewer, body);
        };
    }

    private static Text defaultIdentity(@NotNull PlayerBodyEntity body) {
        Role role = resolveRole(body.getPlayerRoleId());
        MutableText result = Text.translatable("hud.wathe.body.identity").withColor(Colors.RED);
        result.append(roleName(role).withColor(role.color()));
        return result;
    }

    private static Text defaultAffiliation(@NotNull PlayerBodyEntity body) {
        Role role = resolveRole(body.getPlayerRoleId());
        Faction faction = role.getFaction();
        return Text.translatable("hud.wathe.role_name.affiliation_format",
                Text.translatable("hud.wathe.role_name.affiliation." + faction.name().toLowerCase())).withColor(faction.displayColor());
    }

    private static Text defaultKiller(@NotNull ClientPlayerEntity viewer, @NotNull PlayerBodyEntity body) {
        Optional<UUID> killerUuid = body.getKillerUuid();
        if (killerUuid.isEmpty()) {
            return Text.translatable("hud.wathe.body.killer_unknown").withColor(Colors.WHITE);
        }
        UUID uuid = killerUuid.get();
        boolean self = uuid.equals(body.getPlayerUuid());
        Text name = self
                ? Text.translatable("hud.wathe.body.killer_self").formatted(Formatting.DARK_RED)
                : Text.literal(body.getKillerNameSnapshot().isBlank() ? uuid.toString() : body.getKillerNameSnapshot())
                .withColor(resolveRoleColor(body.getKillerRoleId()));
        PlayerBodyHudApi.KillerStatus status = self ? PlayerBodyHudApi.KillerStatus.DEAD
                : PlayerBodyHudApi.resolveKillerStatus(new PlayerBodyHudApi.Context(viewer, body));
        if (status == null) {
            status = resolveDefaultKillerStatus(viewer, uuid);
        }
        return Text.translatable("hud.wathe.body.killer_format", name, killerStatusText(status));
    }

    private static Text killerStatusText(PlayerBodyHudApi.KillerStatus status) {
        return switch (status) {
            /* 状态正文和中括号必须共享同一个父 Text 颜色，不能把括号留在翻译模板里变成白色。 */
            case ALIVE -> Text.literal("[").append(Text.translatable("hud.wathe.body.killer_alive")).append("]").withColor(0x55FF55);
            case DEAD -> Text.literal("[").append(Text.translatable("hud.wathe.body.killer_dead")).append("]").withColor(0xAA0000);
            case OFFLINE -> Text.literal("[").append(Text.translatable("hud.wathe.body.killer_offline")).append("]").withColor(0xAAAAAA);
        };
    }

    private static PlayerBodyHudApi.KillerStatus resolveDefaultKillerStatus(@NotNull ClientPlayerEntity viewer, @NotNull UUID uuid) {
        PlayerEntity entity = viewer.getWorld().getPlayerByUuid(uuid);
        if (entity != null) {
            if (PlayerLifeStateApi.isForensicallyDead(entity)) {
                return PlayerBodyHudApi.KillerStatus.DEAD;
            }
            return GameFunctions.isPlayerAliveAndSurvival(entity)
                    ? PlayerBodyHudApi.KillerStatus.ALIVE
                    : PlayerBodyHudApi.KillerStatus.DEAD;
        }
        PlayerListEntry entry = viewer.networkHandler.getPlayerListEntry(uuid);
        if (entry == null) {
            return PlayerBodyHudApi.KillerStatus.OFFLINE;
        }
        return entry.getGameMode() != null
                && entry.getGameMode() != net.minecraft.world.GameMode.SPECTATOR
                && entry.getGameMode() != net.minecraft.world.GameMode.CREATIVE
                ? PlayerBodyHudApi.KillerStatus.ALIVE
                : PlayerBodyHudApi.KillerStatus.DEAD;
    }

    private static Text obfuscate(@NotNull Text original,
                                  @NotNull PlayerBodyEntity body,
                                  @NotNull PlayerBodyHudApi.BodyHudLine line) {
        int length = MathHelper.clamp(original.getString().length(), 8, 36);
        int seed = Math.abs(body.getUuid().hashCode() * 31 + line.ordinal());
        int count = length + seed % 5;
        return Text.literal("a".repeat(count)).formatted(Formatting.OBFUSCATED);
    }

    private static @Nullable Text resolveFallbackName(@NotNull ClientPlayerEntity viewer, @NotNull UUID uuid) {
        PlayerListEntry entry = viewer.networkHandler.getPlayerListEntry(uuid);
        return entry == null ? null : Text.literal(entry.getProfile().getName()).withColor(Colors.WHITE);
    }

    private static Role resolveRole(@NotNull String roleId) {
        Role role = WatheRoles.getRole(Identifier.tryParse(roleId));
        return role == null ? WatheRoles.CIVILIAN : role;
    }

    private static int resolveRoleColor(@NotNull String roleId) {
        return resolveRole(roleId).color();
    }

    private static MutableText roleName(@NotNull Role role) {
        String key = role.identifier().getNamespace().equals("wathe")
                ? "announcement.role." + role.identifier().getPath()
                : "announcement.role." + role.identifier().getNamespace() + "." + role.identifier().getPath();
        return Text.translatable(key);
    }

    private static String deathReasonKey(@NotNull String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? "death_reason.wathe.generic" : "death_reason." + identifier.getNamespace() + "." + identifier.getPath();
    }

    private static void drawCentered(@NotNull TextRenderer renderer,
                                     @NotNull DrawContext context,
                                     @NotNull Text text,
                                     int y,
                                     int fallbackColor) {
        int width = renderer.getWidth(text);
        context.drawTextWithShadow(renderer, text, -width / 2, y,
                (fallbackColor & 0x00FFFFFF) | ((int) (alpha * 255.0F) << 24));
    }

    private record RenderedLine(@NotNull Text text, int fallbackColor) {
    }
}
