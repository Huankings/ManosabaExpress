package dev.doctor4t.wathe.task;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * 任务点类型枚举。
 *
 * <p>这里描述的是“某个世界坐标可以帮助完成哪类任务”，
 * 而不是玩家 HUD 上显示的任务本身。
 *
 * <p>之所以单独抽一层出来，是因为一个坐标点可能同时承担多个用途：
 * 例如同一个托盘里既可能有可直接吃的食物，也可能有可拿去熔炉烤的生食，
 * 因此服务端缓存与客户端同步都必须支持“一个点对应多个任务点类型”。
 */
public enum TaskPointType {
    BED("hud.task_point.bed", 0x57D6FF),
    KEYED_DOOR("hud.task_point.keyed_door", 0xFFF79B),
    WATER_SOURCE("hud.task_point.water_source", 0x4FA7FF),
    FIRE_SOURCE("hud.task_point.fire_source", 0xFF8B3D),
    FOOD_TRAY("hud.task_point.food_tray", 0x61D95C),
    COCKTAIL_TRAY("hud.task_point.cocktail_tray", 0xFF85A8),
    SEAT("hud.task_point.seat", 0x7AF4E1),
    POTION_TRAY("hud.task_point.potion_tray", 0x8BC0FF),
    NOTE_BLOCK("hud.task_point.note_block", 0x8FA2FF),
    LECTERN("hud.task_point.lectern", 0xFFB15E),
    FISHING_ROD_TRAY("hud.task_point.fishing_rod_tray", 0x6ED4C1),
    FURNACE("hud.task_point.furnace", 0xC4C4C4),
    SMOKER("hud.task_point.smoker", 0xA67A53),
    RAW_FOOD_TRAY("hud.task_point.raw_food_tray", 0xF1D661),
    FUEL_TRAY("hud.task_point.fuel_tray", 0x7E7E7E);

    private final String translationKey;
    private final int color;

    TaskPointType(String translationKey, int color) {
        this.translationKey = translationKey;
        this.color = color;
    }

    public @NotNull String getTranslationKey() {
        return this.translationKey;
    }

    public int getColor() {
        return this.color;
    }

    /**
     * 把类型集合压成一个整数位掩码，方便网络同步。
     *
     * <p>当前任务点类型数量远小于 32，因此用 int 足够承载。
     */
    public static int toBitMask(@NotNull Set<TaskPointType> types) {
        int bitMask = 0;
        for (TaskPointType type : types) {
            bitMask |= 1 << type.ordinal();
        }
        return bitMask;
    }

    /**
     * 把网络里传来的位掩码重新展开为枚举集合。
     */
    public static @NotNull EnumSet<TaskPointType> fromBitMask(int bitMask) {
        EnumSet<TaskPointType> types = EnumSet.noneOf(TaskPointType.class);
        for (TaskPointType type : values()) {
            if ((bitMask & (1 << type.ordinal())) != 0) {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * 把“当前玩家身上的某个任务”映射成应该高亮的任务点类型集合。
     *
     * <p>注意：
     * 1. 不是所有任务都有固定任务点，因此很多任务会返回空集合；
     * 2. 烤吃的任务会对应多个任务点：炉子、烟熏炉、生食托盘、燃料托盘；
     * 3. 多任务模式下，客户端会把所有任务映射后的结果集合并后一起显示。
     */
    public static @NotNull EnumSet<TaskPointType> getTypesForTask(@NotNull PlayerMoodComponent.Task task) {
        return switch (task) {
            case SLEEP -> EnumSet.of(BED);
            case WATER -> EnumSet.of(WATER_SOURCE);
            case FIRE -> EnumSet.of(FIRE_SOURCE);
            case EAT -> EnumSet.of(FOOD_TRAY);
            case DRINK -> EnumSet.of(COCKTAIL_TRAY);
            case SIT -> EnumSet.of(SEAT);
            case POTION -> EnumSet.of(POTION_TRAY);
            case MUSIC -> EnumSet.of(NOTE_BLOCK);
            case BOOK -> EnumSet.of(LECTERN);
            case FISH -> EnumSet.of(FISHING_ROD_TRAY);
            case COOK -> EnumSet.of(FURNACE, SMOKER, RAW_FOOD_TRAY, FUEL_TRAY);
            default -> EnumSet.noneOf(TaskPointType.class);
        };
    }
}
