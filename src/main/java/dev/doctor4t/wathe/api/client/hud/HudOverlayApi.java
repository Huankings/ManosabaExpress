package dev.doctor4t.wathe.api.client.hud;

import dev.doctor4t.wathe.api.Role;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wathe 屏幕 HUD 的通用客户端接入点。
 *
 * <p>这个 API 面向“职业/词条自己的状态提示、全屏遮罩、额外进度条”等通用 HUD，
 * 不替代已经专门公开过的 {@code MoodHudApi}/{@code TimeHudApi}/{@code RoleNameHudApi}。
 * 扩展职业应该优先选择最贴合的专用 API；只有需要自由绘制屏幕叠加内容时才注册这里。</p>
 */
@Environment(EnvType.CLIENT)
public final class HudOverlayApi {
    public static final int DEFAULT_PRIORITY = 0;

    /**
     * HUD 叠加是“后画的盖住先画的”，因此这里约定 priority 越大越晚渲染。
     * 这样狙击镜黑色遮罩这类必须压到最上层的 HUD 可以直接给更高 priority。
     */
    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .thenComparingLong(Entry::order);

    private static final Map<HudOverlayLayer, List<Entry>> RENDERERS = new EnumMap<>(HudOverlayLayer.class);
    private static long nextOrder = 0L;

    static {
        for (HudOverlayLayer layer : HudOverlayLayer.values()) {
            RENDERERS.put(layer, new ArrayList<>());
        }
    }

    private HudOverlayApi() {
    }

    public static void register(@NotNull Identifier id,
                                @NotNull HudOverlayLayer layer,
                                int priority,
                                @NotNull HudOverlayRenderer renderer) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(renderer, "renderer");
        synchronized (RENDERERS) {
            List<Entry> entries = RENDERERS.get(layer);
            entries.removeIf(entry -> entry.id().equals(id));
            entries.add(new Entry(id, layer, priority, nextOrder++, renderer));
            entries.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 注册一个“只有本地玩家是指定职业且仍按 Wathe 存活时才渲染”的 HUD。
     *
     * <p>NoellesRoles / kinssaba 很多旧 HUD mixin 都是先判断职业再画右下角状态；
     * 这里把 {@code GameFunctions.isPlayerAliveAndSurvival} 的结果收口到 Wathe，
     * 避免扩展迁移时有的职业漏判断，死亡后仍显示旧状态栏。</p>
     */
    public static void registerAliveRole(@NotNull Identifier id,
                                         @NotNull HudOverlayLayer layer,
                                         int priority,
                                         @NotNull Role role,
                                         @NotNull HudOverlayRenderer renderer) {
        Objects.requireNonNull(role, "role");
        register(id, layer, priority, context -> {
            if (context.isAliveRole(role)) {
                renderer.render(context);
            }
        });
    }

    public static void render(@NotNull HudOverlayLayer layer, @NotNull HudOverlayContext context) {
        for (Entry entry : snapshot(layer)) {
            entry.renderer().render(context);
        }
    }

    private static List<Entry> snapshot(@NotNull HudOverlayLayer layer) {
        synchronized (RENDERERS) {
            return List.copyOf(RENDERERS.get(layer));
        }
    }

    @FunctionalInterface
    public interface HudOverlayRenderer {
        void render(@NotNull HudOverlayContext context);
    }

    private record Entry(@NotNull Identifier id,
                         @NotNull HudOverlayLayer layer,
                         int priority,
                         long order,
                         @NotNull HudOverlayRenderer renderer) {
    }
}
