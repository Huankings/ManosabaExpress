package dev.doctor4t.wathe.api.client.appearance;

import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家与玩家尸体外观的公开客户端接入点。
 *
 * <p>扩展职业只需要在 client initializer 中注册 handler，不要再分别 mixin 玩家渲染器、
 * 披风渲染器、第一人称手臂、Wathe 尸体渲染器。Wathe 会在统一入口询问这里的结果。</p>
 *
 * <p>priority 越大越先执行；handler 返回 {@code null} 表示 PASS。</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerAppearanceApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<PlayerEntry> PLAYER_COMPARATOR =
            Comparator.<PlayerEntry>comparingInt(PlayerEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PlayerEntry::order).reversed());
    private static final Comparator<BodyEntry> BODY_COMPARATOR =
            Comparator.<BodyEntry>comparingInt(BodyEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(BodyEntry::order).reversed());

    private static final List<PlayerEntry> PLAYER_SKIN_HANDLERS = new ArrayList<>();
    private static final List<BodyEntry> BODY_SKIN_HANDLERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private PlayerAppearanceApi() {
    }

    public static void registerPlayerSkin(@NotNull Identifier id, int priority, @NotNull PlayerSkinHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (PLAYER_SKIN_HANDLERS) {
            PLAYER_SKIN_HANDLERS.removeIf(entry -> entry.id().equals(id));
            PLAYER_SKIN_HANDLERS.add(new PlayerEntry(id, priority, nextOrder++, handler));
            PLAYER_SKIN_HANDLERS.sort(PLAYER_COMPARATOR);
        }
    }

    public static void registerBodySkin(@NotNull Identifier id, int priority, @NotNull BodySkinHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (BODY_SKIN_HANDLERS) {
            BODY_SKIN_HANDLERS.removeIf(entry -> entry.id().equals(id));
            BODY_SKIN_HANDLERS.add(new BodyEntry(id, priority, nextOrder++, handler));
            BODY_SKIN_HANDLERS.sort(BODY_COMPARATOR);
        }
    }

    public static @Nullable SkinTextures resolvePlayerSkin(@NotNull AbstractClientPlayerEntity player) {
        for (PlayerEntry entry : playerSnapshot()) {
            SkinTextures result = entry.handler().getSkin(player);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 解析尸体最终显示皮肤。
     *
     * <p>先问客户端高优先级 handler，用于灵术师出窍这种“只影响自己客户端”的视觉覆盖；
     * 如果没有覆盖，再按尸体保存的 appearance UUID 解析。appearance UUID 为空时自然回到真实死者。</p>
     */
    public static @NotNull SkinTextures resolveBodySkin(@NotNull PlayerBodyEntity body) {
        for (BodyEntry entry : bodySnapshot()) {
            SkinTextures result = entry.handler().getSkin(body);
            if (result != null) {
                return result;
            }
        }
        return resolveOriginalSkinTextures(body.getAppearanceUuid(), true);
    }

    /**
     * 只按 UUID 取“原始皮肤”，绝不读取世界中玩家实体的 getSkinTextures()。
     *
     * <p>这是统一伪装系统最关键的防套娃规则：如果 A 变成 B、B 又变成 A，
     * 读取实体当前皮肤会把对方的临时伪装再次套进来；读取玩家列表/Wathe 缓存则能稳定拿到 UUID 原皮。</p>
     */
    public static @NotNull SkinTextures resolveOriginalSkinTextures(@Nullable UUID playerUuid, boolean allowWatheCacheFallback) {
        UUID uuid = playerUuid == null ? PlayerBodyEntity.FALLBACK_PLAYER_UUID : playerUuid;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity localPlayer = client.player;

        if (localPlayer != null && localPlayer.networkHandler != null) {
            PlayerListEntry entry = localPlayer.networkHandler.getPlayerListEntry(uuid);
            if (entry != null) {
                return entry.getSkinTextures();
            }
        }

        if (allowWatheCacheFallback && WatheClient.PLAYER_ENTRIES_CACHE != null) {
            PlayerListEntry cachedEntry = WatheClient.PLAYER_ENTRIES_CACHE.get(uuid);
            if (cachedEntry != null) {
                return cachedEntry.getSkinTextures();
            }
        }

        return DefaultSkinHelper.getSkinTextures(uuid);
    }

    public static @Nullable String resolveOriginalPlayerName(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity localPlayer = client.player;
        if (localPlayer != null && localPlayer.networkHandler != null) {
            PlayerListEntry entry = localPlayer.networkHandler.getPlayerListEntry(playerUuid);
            if (entry != null && entry.getProfile() != null) {
                return entry.getProfile().getName();
            }
        }

        /*
         * 二次进服或资源重载早期，Wathe 的玩家缓存可能还没恢复。
         * 名字解析只是准心 HUD 的显示兜底，不能因为缓存为空把渲染线程打崩。
         */
        if (WatheClient.PLAYER_ENTRIES_CACHE == null) {
            return null;
        }
        PlayerListEntry cachedEntry = WatheClient.PLAYER_ENTRIES_CACHE.get(playerUuid);
        return cachedEntry == null || cachedEntry.getProfile() == null ? null : cachedEntry.getProfile().getName();
    }

    private static List<PlayerEntry> playerSnapshot() {
        synchronized (PLAYER_SKIN_HANDLERS) {
            return List.copyOf(PLAYER_SKIN_HANDLERS);
        }
    }

    private static List<BodyEntry> bodySnapshot() {
        synchronized (BODY_SKIN_HANDLERS) {
            return List.copyOf(BODY_SKIN_HANDLERS);
        }
    }

    @FunctionalInterface
    public interface PlayerSkinHandler {
        @Nullable SkinTextures getSkin(@NotNull AbstractClientPlayerEntity player);
    }

    @FunctionalInterface
    public interface BodySkinHandler {
        @Nullable SkinTextures getSkin(@NotNull PlayerBodyEntity body);
    }

    private record PlayerEntry(@NotNull Identifier id, int priority, long order, @NotNull PlayerSkinHandler handler) {
    }

    private record BodyEntry(@NotNull Identifier id, int priority, long order, @NotNull BodySkinHandler handler) {
    }
}
