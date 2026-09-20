package dev.doctor4t.wathe.client.appearance;

import com.mojang.authlib.GameProfile;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.client.appearance.PlayerAppearanceApi;
import dev.doctor4t.wathe.api.client.gui.RoleNameHudApi;
import dev.doctor4t.wathe.cca.DebugDisguiseComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 客户端读取服务器调试变形状态，并接入 Wathe 的统一外观与准心名字 API。 */
public final class DebugDisguiseClientHandler {
    /**
     * 调试变形必须是整条 provider 链的最后兜底。
     * 使用最小整数后，任何扩展模组的正常变形规则只要返回有效结果，就一定优先于本规则。
     */
    private static final int DEBUG_PRIORITY = Integer.MIN_VALUE;
    /* SkinProvider 的 future 可能在渲染线程之外完成，因此这里必须使用并发容器。 */
    private static final Map<UUID, SkinTextures> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> SKIN_REQUESTS = ConcurrentHashMap.newKeySet();
    private static boolean registered;

    private DebugDisguiseClientHandler() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayerAppearanceApi.registerPlayerSkin(
                Wathe.id("debug_disguise/player_skin"),
                DEBUG_PRIORITY,
                DebugDisguiseClientHandler::resolveSkin
        );
        RoleNameHudApi.registerName(
                Wathe.id("debug_disguise/role_name"),
                DEBUG_PRIORITY,
                (viewer, target, originalName) -> {
                    DebugDisguiseComponent.DisguiseEntry entry = resolveEntry(target.getUuid());
                    return entry == null ? null : Text.literal(entry.appearanceName());
                }
        );
    }

    private static @Nullable SkinTextures resolveSkin(@NotNull AbstractClientPlayerEntity player) {
        DebugDisguiseComponent.DisguiseEntry entry = resolveEntry(player.getUuid());
        if (entry == null) {
            return null;
        }

        UUID appearanceUuid = entry.appearanceUuid();
        SkinTextures cached = SKIN_CACHE.get(appearanceUuid);
        if (cached != null) {
            return cached;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        GameProfile profile = new GameProfile(appearanceUuid, entry.appearanceName());
        /*
         * 被模仿者可能已经离线，甚至观察者可能是在其离线后才进入服务器。
         * 因此不能只依赖 tab 列表；首次遇到该 UUID 时主动交给原版 SkinProvider 异步解析，
         * 完成前使用原版同步兜底，完成后缓存完整皮肤、披风和模型信息。
         */
        if (SKIN_REQUESTS.add(appearanceUuid)) {
            client.getSkinProvider().fetchSkinTextures(profile).whenComplete((textures, throwable) -> {
                if (textures != null) {
                    SKIN_CACHE.put(appearanceUuid, textures);
                }
                // 请求失败也必须解锁，让后续渲染帧仍有机会重新请求，而不是永久卡在默认皮肤。
                SKIN_REQUESTS.remove(appearanceUuid);
            });
        }
        return client.getSkinProvider().getSkinTextures(profile);
    }

    private static @Nullable DebugDisguiseComponent.DisguiseEntry resolveEntry(@NotNull UUID playerUuid) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return null;
        }

        DebugDisguiseComponent component = DebugDisguiseComponent.KEY.get(client.world.getScoreboard());
        long now = System.currentTimeMillis();
        DebugDisguiseComponent.DisguiseEntry personal = component.getPlayerEntry(playerUuid);
        if (DebugDisguiseComponent.isActive(personal, now)) {
            return personal;
        }
        if (component.isExcludedFromGlobal(playerUuid)) {
            return null;
        }
        DebugDisguiseComponent.DisguiseEntry global = component.getGlobalEntry();
        return DebugDisguiseComponent.isActive(global, now) ? global : null;
    }
}
