package dev.doctor4t.wathe.api.task;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 任务点透视中的一个可显示类型。
 *
 * <p>任务点类型描述的是“某个坐标可以帮助完成哪类任务”，不是任务本身。
 * 例如“烤吃的”任务会同时关联熔炉、烟熏炉、生食托盘和燃料托盘几个任务点。
 * 任务与任务点分开注册后，扩展 mod 可以复用 Wathe 现有任务点，也可以注册自己的任务点。</p>
 */
public record TaskPointDefinition(
        @NotNull Identifier id,
        @NotNull String translationKey,
        int color
) {
    public TaskPointDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(translationKey, "translationKey");
    }
}
