package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.cca.MapVariablesWorldComponent;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 扩展任务点扫描器看到的单格扫描上下文。
 *
 * <p>Wathe 会继续负责“只扫描当前列车复制区域、只保留游戏区域内坐标”的大边界。
 * 扩展 handler 只需要检查当前方块 / 方块实体是否符合自己的任务点条件，
 * 符合时调用 {@link #addTaskPoint(Identifier)} 即可。</p>
 */
public final class TaskPointScanContext {
    private final ServerWorld world;
    private final MapVariablesWorldComponent mapVariables;
    private final BlockPos pos;
    private final BlockState state;
    private final BlockEntity blockEntity;
    private final Consumer<Identifier> taskPointAdder;

    public TaskPointScanContext(
            @NotNull ServerWorld world,
            @NotNull MapVariablesWorldComponent mapVariables,
            @NotNull BlockPos pos,
            @NotNull BlockState state,
            @Nullable BlockEntity blockEntity,
            @NotNull Consumer<Identifier> taskPointAdder
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.mapVariables = Objects.requireNonNull(mapVariables, "mapVariables");
        this.pos = Objects.requireNonNull(pos, "pos").toImmutable();
        this.state = Objects.requireNonNull(state, "state");
        this.blockEntity = blockEntity;
        this.taskPointAdder = Objects.requireNonNull(taskPointAdder, "taskPointAdder");
    }

    public @NotNull ServerWorld world() {
        return this.world;
    }

    public @NotNull MapVariablesWorldComponent mapVariables() {
        return this.mapVariables;
    }

    public @NotNull BlockPos pos() {
        return this.pos;
    }

    public @NotNull BlockState state() {
        return this.state;
    }

    public @Nullable BlockEntity blockEntity() {
        return this.blockEntity;
    }

    /**
     * 把当前坐标标记成某个任务点类型。
     *
     * <p>这里会先确认任务点类型已经注册，避免扩展因为拼错 id 把客户端同步成无法显示的孤儿数据。</p>
     */
    public void addTaskPoint(@NotNull Identifier taskPointId) {
        if (MoodTaskPointApi.isRegistered(taskPointId)) {
            this.taskPointAdder.accept(taskPointId);
        }
    }
}
