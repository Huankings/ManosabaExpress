package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wathe 心情任务点透视的公开注册入口。
 *
 * <p>旧版任务点类型使用 enum + bitmask 同步，扩展 mod 无法追加新的类型。
 * 现在任务点用 {@link Identifier} 注册和同步，扩展只要注册定义和扫描 handler，
 * 就能让自己的任务点出现在同一套任务点透视 HUD 中。</p>
 */
public final class MoodTaskPointApi {
    public static final int DEFAULT_PRIORITY = 0;

    public static final Identifier BED = Wathe.id("bed");
    public static final Identifier KEYED_DOOR = Wathe.id("keyed_door");
    public static final Identifier WATER_SOURCE = Wathe.id("water_source");
    public static final Identifier FIRE_SOURCE = Wathe.id("fire_source");
    public static final Identifier FOOD_TRAY = Wathe.id("food_tray");
    public static final Identifier COCKTAIL_TRAY = Wathe.id("cocktail_tray");
    public static final Identifier SEAT = Wathe.id("seat");
    public static final Identifier POTION_TRAY = Wathe.id("potion_tray");
    public static final Identifier NOTE_BLOCK = Wathe.id("note_block");
    public static final Identifier LECTERN = Wathe.id("lectern");
    public static final Identifier FISHING_ROD_TRAY = Wathe.id("fishing_rod_tray");
    public static final Identifier FURNACE = Wathe.id("furnace");
    public static final Identifier SMOKER = Wathe.id("smoker");
    public static final Identifier RAW_FOOD_TRAY = Wathe.id("raw_food_tray");
    public static final Identifier FUEL_TRAY = Wathe.id("fuel_tray");

    private static final Map<Identifier, TaskPointDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final ArrayList<PrioritizedScanHandler> SCAN_HANDLERS = new ArrayList<>();
    private static long nextOrder = 0L;

    static {
        registerBuiltInTaskPoints();
    }

    private MoodTaskPointApi() {
    }

    /**
     * 注册或替换一个任务点类型定义。
     *
     * <p>同 id 重复注册时采用“后注册覆盖定义”的语义，方便开发期热替换或扩展主动修正自己的颜色/名称。</p>
     */
    public static synchronized void registerTaskPoint(@NotNull TaskPointDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        DEFINITIONS.put(definition.id(), definition);
    }

    public static void registerTaskPoint(@NotNull Identifier id, @NotNull String translationKey, int color) {
        registerTaskPoint(new TaskPointDefinition(id, translationKey, color));
    }

    /**
     * 注册一个额外扫描 handler。
     *
     * <p>Wathe 内置扫描器会逐格构造 {@link TaskPointScanContext} 并调用这些 handler。
     * handler 只负责判断“当前这一格是不是自己的任务点”，不要在这里再次大范围扫世界。</p>
     */
    public static synchronized void registerScanHandler(
            @NotNull Identifier id,
            int priority,
            @NotNull TaskPointScanHandler handler
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        SCAN_HANDLERS.removeIf(entry -> entry.id().equals(id));
        SCAN_HANDLERS.add(new PrioritizedScanHandler(id, priority, nextOrder++, handler));
        SCAN_HANDLERS.sort(
                Comparator.<PrioritizedScanHandler>comparingInt(PrioritizedScanHandler::priority)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(PrioritizedScanHandler::order).reversed())
        );
    }

    public static void scanExtraTaskPoints(@NotNull TaskPointScanContext context) {
        for (PrioritizedScanHandler entry : scanHandlerSnapshot()) {
            entry.handler().scan(context);
        }
    }

    public static synchronized @Nullable TaskPointDefinition getDefinition(@NotNull Identifier id) {
        return DEFINITIONS.get(id);
    }

    public static synchronized boolean isRegistered(@NotNull Identifier id) {
        return DEFINITIONS.containsKey(id);
    }

    public static synchronized @NotNull Collection<TaskPointDefinition> getDefinitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static synchronized @NotNull List<Identifier> getRegisteredIds() {
        return List.copyOf(DEFINITIONS.keySet());
    }

    public static @NotNull String getTranslationKey(@NotNull Identifier id) {
        TaskPointDefinition definition = getDefinition(id);
        return definition == null ? "hud.task_point.unknown" : definition.translationKey();
    }

    public static int getColor(@NotNull Identifier id) {
        TaskPointDefinition definition = getDefinition(id);
        return definition == null ? 0xFFFFFF : definition.color();
    }

    /**
     * 读取旧存档里的 enum 名称或新存档里的 Identifier 字符串。
     */
    public static @Nullable Identifier resolveSerializedId(@NotNull String serializedId) {
        Identifier parsed = Identifier.tryParse(serializedId);
        if (parsed != null && isRegistered(parsed)) {
            return parsed;
        }
        return switch (serializedId) {
            case "BED" -> BED;
            case "KEYED_DOOR" -> KEYED_DOOR;
            case "WATER_SOURCE" -> WATER_SOURCE;
            case "FIRE_SOURCE" -> FIRE_SOURCE;
            case "FOOD_TRAY" -> FOOD_TRAY;
            case "COCKTAIL_TRAY" -> COCKTAIL_TRAY;
            case "SEAT" -> SEAT;
            case "POTION_TRAY" -> POTION_TRAY;
            case "NOTE_BLOCK" -> NOTE_BLOCK;
            case "LECTERN" -> LECTERN;
            case "FISHING_ROD_TRAY" -> FISHING_ROD_TRAY;
            case "FURNACE" -> FURNACE;
            case "SMOKER" -> SMOKER;
            case "RAW_FOOD_TRAY" -> RAW_FOOD_TRAY;
            case "FUEL_TRAY" -> FUEL_TRAY;
            default -> null;
        };
    }

    private static synchronized @NotNull List<PrioritizedScanHandler> scanHandlerSnapshot() {
        return List.copyOf(SCAN_HANDLERS);
    }

    private static void registerBuiltInTaskPoints() {
        registerTaskPoint(BED, "hud.task_point.bed", 0x57D6FF);
        registerTaskPoint(KEYED_DOOR, "hud.task_point.keyed_door", 0xFFF79B);
        registerTaskPoint(WATER_SOURCE, "hud.task_point.water_source", 0x4FA7FF);
        registerTaskPoint(FIRE_SOURCE, "hud.task_point.fire_source", 0xFF8B3D);
        registerTaskPoint(FOOD_TRAY, "hud.task_point.food_tray", 0x61D95C);
        registerTaskPoint(COCKTAIL_TRAY, "hud.task_point.cocktail_tray", 0xFF85A8);
        registerTaskPoint(SEAT, "hud.task_point.seat", 0x7AF4E1);
        registerTaskPoint(POTION_TRAY, "hud.task_point.potion_tray", 0x8BC0FF);
        registerTaskPoint(NOTE_BLOCK, "hud.task_point.note_block", 0x8FA2FF);
        registerTaskPoint(LECTERN, "hud.task_point.lectern", 0xFFB15E);
        registerTaskPoint(FISHING_ROD_TRAY, "hud.task_point.fishing_rod_tray", 0x6ED4C1);
        registerTaskPoint(FURNACE, "hud.task_point.furnace", 0xC4C4C4);
        registerTaskPoint(SMOKER, "hud.task_point.smoker", 0xA67A53);
        registerTaskPoint(RAW_FOOD_TRAY, "hud.task_point.raw_food_tray", 0xF1D661);
        registerTaskPoint(FUEL_TRAY, "hud.task_point.fuel_tray", 0x7E7E7E);
    }

    @FunctionalInterface
    public interface TaskPointScanHandler {
        void scan(@NotNull TaskPointScanContext context);
    }

    private record PrioritizedScanHandler(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull TaskPointScanHandler handler
    ) {
    }
}
