package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.task.TaskPointType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 世界级任务点缓存组件。
 *
 * <p>这里专门保存“当前地图里已经扫描出来的任务点坐标”。
 * 之所以做成世界组件，是为了：
 * 1. 让手动重载后的结果能稳定挂在当前世界上；
 * 2. 新玩家进服时可以直接拿当前缓存同步；
 * 3. 自动开局刷新开关也能跟着世界一起持久化。
 *
 * <p>注意这里虽然实现了 {@link AutoSyncedComponent}，但任务点大表的客户端同步
 * 实际上仍然走自定义 payload。这样我们可以精确控制同步时机，避免把整张表塞进
 * 现有的定时组件同步里。
 */
public class TaskPointWorldComponent implements AutoSyncedComponent {
    public static final ComponentKey<TaskPointWorldComponent> KEY =
            ComponentRegistry.getOrCreate(Wathe.id("task_points"), TaskPointWorldComponent.class);

    private final World world;

    /**
     * 当前任务点缓存。
     * key 是任务点方块坐标，value 是这个坐标同时承担的任务点类型集合。
     */
    private final HashMap<BlockPos, EnumSet<TaskPointType>> taskPoints = new HashMap<>();

    /**
     * 是否在每局开始时自动重扫一次任务点。
     * 默认开启，方便地图被重新复制或手动改动后自动更新记录。
     */
    private boolean autoRefreshOnGameStart = true;

    public TaskPointWorldComponent(World world) {
        this.world = world;
    }

    /**
     * 返回任务点缓存的深拷贝快照。
     *
     * <p>之所以不直接把内部 map 暴露出去，是为了避免外部调用方误改组件内部状态。
     */
    public @NotNull HashMap<BlockPos, EnumSet<TaskPointType>> createSnapshot() {
        return copyTaskPoints(this.taskPoints);
    }

    /**
     * 用新的扫描结果整体替换缓存。
     */
    public void setTaskPoints(@NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints) {
        this.taskPoints.clear();
        this.taskPoints.putAll(copyTaskPoints(taskPoints));
    }

    public int size() {
        return this.taskPoints.size();
    }

    public boolean isAutoRefreshOnGameStart() {
        return this.autoRefreshOnGameStart;
    }

    public void setAutoRefreshOnGameStart(boolean autoRefreshOnGameStart) {
        this.autoRefreshOnGameStart = autoRefreshOnGameStart;
    }

    private static @NotNull HashMap<BlockPos, EnumSet<TaskPointType>> copyTaskPoints(@NotNull Map<BlockPos, EnumSet<TaskPointType>> source) {
        HashMap<BlockPos, EnumSet<TaskPointType>> copy = new HashMap<>();
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : source.entrySet()) {
            copy.put(entry.getKey().toImmutable(), EnumSet.copyOf(entry.getValue()));
        }
        return copy;
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        this.taskPoints.clear();
        this.autoRefreshOnGameStart = !tag.contains("AutoRefreshOnGameStart", NbtElement.BYTE_TYPE)
                || tag.getBoolean("AutoRefreshOnGameStart");

        if (!tag.contains("TaskPoints", NbtElement.LIST_TYPE)) {
            return;
        }

        NbtList taskPointList = tag.getList("TaskPoints", NbtElement.COMPOUND_TYPE);
        for (NbtElement element : taskPointList) {
            if (!(element instanceof NbtCompound pointTag)) {
                continue;
            }

            BlockPos pos = new BlockPos(
                    pointTag.getInt("X"),
                    pointTag.getInt("Y"),
                    pointTag.getInt("Z")
            );

            EnumSet<TaskPointType> types = EnumSet.noneOf(TaskPointType.class);
            if (pointTag.contains("Types", NbtElement.LIST_TYPE)) {
                NbtList typesTag = pointTag.getList("Types", NbtElement.STRING_TYPE);
                for (NbtElement typeElement : typesTag) {
                    if (!(typeElement instanceof NbtString typeString)) {
                        continue;
                    }

                    try {
                        types.add(TaskPointType.valueOf(typeString.asString()));
                    } catch (IllegalArgumentException ignored) {
                        // 兼容未来删改任务点类型时的旧存档，读不到的旧类型直接跳过即可。
                    }
                }
            }

            if (!types.isEmpty()) {
                this.taskPoints.put(pos, types);
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.@NotNull WrapperLookup registryLookup) {
        tag.putBoolean("AutoRefreshOnGameStart", this.autoRefreshOnGameStart);

        NbtList taskPointList = new NbtList();
        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : this.taskPoints.entrySet()) {
            NbtCompound pointTag = new NbtCompound();
            pointTag.putInt("X", entry.getKey().getX());
            pointTag.putInt("Y", entry.getKey().getY());
            pointTag.putInt("Z", entry.getKey().getZ());

            NbtList typesTag = new NbtList();
            for (TaskPointType type : entry.getValue()) {
                typesTag.add(NbtString.of(type.name()));
            }
            pointTag.put("Types", typesTag);

            taskPointList.add(pointTag);
        }

        tag.put("TaskPoints", taskPointList);
    }
}
