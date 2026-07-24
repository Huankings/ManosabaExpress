package dev.doctor4t.wathe.api.client.inventory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Wathe 背包按钮公开 API。
 *
 * <p>扩展 mod 应该在客户端初始化时注册 provider，不再 mixin
 * {@code LimitedInventoryScreen}/{@code InventoryScreen}/{@code CreativeInventoryScreen}。
 * Wathe 会按界面类型 LIMITED / VANILLA / CREATIVE 创建扩展实例，并统一调度 init、render、
 * tick、close 和“背包键是否允许关闭”。</p>
 */
@Environment(EnvType.CLIENT)
public final class InventoryButtonApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<ProviderEntry> PROVIDER_COMPARATOR =
            Comparator.<ProviderEntry>comparingInt(ProviderEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ProviderEntry::order).reversed());

    private static final List<ProviderEntry> PROVIDERS = new ArrayList<>();
    private static final Map<Screen, ScreenState> SCREEN_STATES = new WeakHashMap<>();
    private static long nextOrder = 0L;

    private InventoryButtonApi() {
    }

    public static synchronized void registerProvider(@NotNull Identifier id,
                                                     int priority,
                                                     @NotNull InventoryButtonProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new ProviderEntry(id, priority, nextOrder++, provider));
        PROVIDERS.sort(PROVIDER_COMPARATOR);
    }

    public static void initializeScreen(@NotNull Screen screen,
                                        @NotNull InventoryScreenType type,
                                        @Nullable ClientPlayerEntity player,
                                        @NotNull TextRenderer textRenderer,
                                        @NotNull WidgetAdder widgetAdder,
                                        int width,
                                        int height,
                                        int backgroundX,
                                        int backgroundY,
                                        int backgroundWidth,
                                        int backgroundHeight) {
        closeScreen(screen);

        ScreenState state = new ScreenState(screen, type, player, textRenderer, widgetAdder, width, height, backgroundX, backgroundY, backgroundWidth, backgroundHeight);
        synchronized (SCREEN_STATES) {
            SCREEN_STATES.put(screen, state);
        }

        InventoryButtonContext context = state.context();
        for (ProviderEntry entry : providerSnapshot()) {
            InventoryButtonExtension extension = entry.provider().create(context);
            if (extension != null) {
                state.extensions().add(extension);
                extension.init(context);
            }
        }
    }

    public static void tickScreen(@NotNull Screen screen) {
        ScreenState state = getState(screen);
        if (state == null) {
            return;
        }
        InventoryButtonContext context = state.context();
        for (InventoryButtonExtension extension : List.copyOf(state.extensions())) {
            extension.tick(context);
        }
    }

    public static void renderScreen(@NotNull Screen screen,
                                    @NotNull DrawContext drawContext,
                                    int mouseX,
                                    int mouseY,
                                    float delta) {
        ScreenState state = getState(screen);
        if (state == null) {
            return;
        }
        InventoryButtonContext context = state.context();
        for (InventoryButtonExtension extension : List.copyOf(state.extensions())) {
            extension.render(context, drawContext, mouseX, mouseY, delta);
        }
    }

    public static boolean allowInventoryKeyClose(@NotNull Screen screen, int keyCode, int scanCode) {
        ScreenState state = getState(screen);
        if (state == null) {
            return true;
        }
        InventoryButtonContext context = state.context();
        for (InventoryButtonExtension extension : List.copyOf(state.extensions())) {
            if (!extension.allowInventoryKeyClose(context, keyCode, scanCode)) {
                return false;
            }
        }
        return true;
    }

    public static void closeScreen(@NotNull Screen screen) {
        ScreenState state;
        synchronized (SCREEN_STATES) {
            state = SCREEN_STATES.remove(screen);
        }
        if (state == null) {
            return;
        }
        InventoryButtonContext context = state.context();
        for (InventoryButtonExtension extension : List.copyOf(state.extensions())) {
            extension.close(context);
        }
        state.clearAllGroups();
    }

    private static synchronized List<ProviderEntry> providerSnapshot() {
        return List.copyOf(PROVIDERS);
    }

    private static @Nullable ScreenState getState(@NotNull Screen screen) {
        synchronized (SCREEN_STATES) {
            return SCREEN_STATES.get(screen);
        }
    }

    @Environment(EnvType.CLIENT)
    @FunctionalInterface
    public interface WidgetAdder {
        @NotNull ClickableWidget add(@NotNull ClickableWidget widget);
    }

    static final class ScreenState {
        private final InventoryButtonContext context;
        private final List<InventoryButtonExtension> extensions = new ArrayList<>();
        private final Map<Identifier, List<ClickableWidget>> groups = new HashMap<>();

        private ScreenState(@NotNull Screen screen,
                            @NotNull InventoryScreenType type,
                            @Nullable ClientPlayerEntity player,
                            @NotNull TextRenderer textRenderer,
                            @NotNull WidgetAdder widgetAdder,
                            int width,
                            int height,
                            int backgroundX,
                            int backgroundY,
                            int backgroundWidth,
                            int backgroundHeight) {
            this.context = new InventoryButtonContext(screen, type, player, textRenderer, widgetAdder, this, width, height, backgroundX, backgroundY, backgroundWidth, backgroundHeight);
        }

        private @NotNull InventoryButtonContext context() {
            return this.context;
        }

        private @NotNull List<InventoryButtonExtension> extensions() {
            return this.extensions;
        }

        void addToGroup(@NotNull Identifier groupId, @NotNull ClickableWidget widget) {
            this.groups.computeIfAbsent(groupId, id -> new ArrayList<>()).add(widget);
        }

        void clearGroup(@NotNull Identifier groupId) {
            List<ClickableWidget> widgets = this.groups.remove(groupId);
            if (widgets == null) {
                return;
            }
            for (ClickableWidget widget : widgets) {
                disableWidget(widget);
            }
        }

        void setGroupVisible(@NotNull Identifier groupId, boolean visible) {
            List<ClickableWidget> widgets = this.groups.get(groupId);
            if (widgets == null) {
                return;
            }
            for (ClickableWidget widget : widgets) {
                widget.visible = visible;
                widget.active = visible;
                if (!visible) {
                    widget.setFocused(false);
                }
            }
        }

        private void clearAllGroups() {
            for (Identifier groupId : List.copyOf(this.groups.keySet())) {
                this.clearGroup(groupId);
            }
            this.groups.clear();
        }

        private static void disableWidget(@NotNull ClickableWidget widget) {
            widget.visible = false;
            widget.active = false;
            widget.setFocused(false);
        }
    }

    private record ProviderEntry(@NotNull Identifier id, int priority, long order, @NotNull InventoryButtonProvider provider) {
    }
}
