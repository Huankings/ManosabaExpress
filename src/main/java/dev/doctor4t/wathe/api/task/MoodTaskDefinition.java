package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个可被 Wathe 心情系统发放、保存、渲染和完成的任务定义。
 *
 * <p>旧版心情任务写死在 {@link PlayerMoodComponent.Task} 枚举里，扩展 mod 只能复用本体已有任务。
 * 现在任务定义改成注册式结构：Wathe 本体会把原有任务注册成内置定义，扩展 mod 也可以注册自己的任务。
 * 真正挂在玩家身上的运行时状态仍然由 {@link PlayerMoodComponent.TrainTask} 承载，方便复用旧任务 tick / NBT 逻辑。</p>
 *
 * <p>注意 {@link Builder} 默认不会把任务加入随机池。这样扩展任务注册后默认只能被指定发放，
 * 避免扩展职业的专属任务突然混进所有玩家的普通心情随机任务里。</p>
 */
public final class MoodTaskDefinition {
    private final Identifier id;
    private final String translationKey;
    private final TaskFactory factory;
    private final TaskNbtReader nbtReader;
    private final boolean randomlyAssignable;
    private final float randomWeight;
    private final Set<Identifier> taskPointIds;
    private final PlayerMoodComponent.Task legacyTask;

    private MoodTaskDefinition(@NotNull Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.translationKey = Objects.requireNonNull(builder.translationKey, "translationKey");
        this.factory = Objects.requireNonNull(builder.factory, "factory");
        this.nbtReader = Objects.requireNonNull(builder.nbtReader, "nbtReader");
        this.randomlyAssignable = builder.randomlyAssignable;
        this.randomWeight = Math.max(0.001F, builder.randomWeight);
        this.taskPointIds = Set.copyOf(builder.taskPointIds);
        this.legacyTask = builder.legacyTask;
    }

    public @NotNull Identifier id() {
        return this.id;
    }

    public @NotNull String translationKey() {
        return this.translationKey;
    }

    public boolean randomlyAssignable() {
        return this.randomlyAssignable;
    }

    public float randomWeight() {
        return this.randomWeight;
    }

    public @NotNull Set<Identifier> taskPointIds() {
        return this.taskPointIds;
    }

    public @Nullable PlayerMoodComponent.Task legacyTask() {
        return this.legacyTask;
    }

    public @NotNull PlayerMoodComponent.TrainTask create(@NotNull PlayerEntity player) {
        return this.factory.create(player);
    }

    public @NotNull PlayerMoodComponent.TrainTask read(@NotNull PlayerEntity player, @NotNull NbtCompound nbt) {
        return this.nbtReader.read(player, nbt);
    }

    public static @NotNull Builder builder(
            @NotNull Identifier id,
            @NotNull String translationKey,
            @NotNull TaskFactory factory,
            @NotNull TaskNbtReader nbtReader
    ) {
        return new Builder(id, translationKey, factory, nbtReader);
    }

    @FunctionalInterface
    public interface TaskFactory {
        @NotNull PlayerMoodComponent.TrainTask create(@NotNull PlayerEntity player);
    }

    @FunctionalInterface
    public interface TaskNbtReader {
        @NotNull PlayerMoodComponent.TrainTask read(@NotNull PlayerEntity player, @NotNull NbtCompound nbt);
    }

    public static final class Builder {
        private final Identifier id;
        private final String translationKey;
        private final TaskFactory factory;
        private final TaskNbtReader nbtReader;
        private boolean randomlyAssignable = false;
        private float randomWeight = 1.0F;
        private final LinkedHashSet<Identifier> taskPointIds = new LinkedHashSet<>();
        private PlayerMoodComponent.Task legacyTask = null;

        private Builder(
                @NotNull Identifier id,
                @NotNull String translationKey,
                @NotNull TaskFactory factory,
                @NotNull TaskNbtReader nbtReader
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.translationKey = Objects.requireNonNull(translationKey, "translationKey");
            this.factory = Objects.requireNonNull(factory, "factory");
            this.nbtReader = Objects.requireNonNull(nbtReader, "nbtReader");
        }

        /**
         * 把任务加入普通随机池。
         *
         * <p>Wathe 内置任务会显式调用这里；扩展任务如果没有调用，就只会被 API 或调试指令指定发放。</p>
         */
        public @NotNull Builder randomlyAssignable() {
            return this.randomlyAssignable(true);
        }

        public @NotNull Builder randomlyAssignable(boolean randomlyAssignable) {
            this.randomlyAssignable = randomlyAssignable;
            return this;
        }

        /**
         * 设置进入随机池后的基础权重。
         *
         * <p>玩家重复抽到同一个任务后，Wathe 仍会在运行时按“被抽到次数”降低实际权重；
         * 这里的数值只表示该任务第一次参与随机时的基础概率。</p>
         */
        public @NotNull Builder randomWeight(float randomWeight) {
            this.randomWeight = randomWeight;
            return this;
        }

        public @NotNull Builder taskPoints(@NotNull Identifier... taskPointIds) {
            this.taskPointIds.addAll(List.of(taskPointIds));
            return this;
        }

        public @NotNull Builder taskPoints(@NotNull Collection<Identifier> taskPointIds) {
            this.taskPointIds.addAll(taskPointIds);
            return this;
        }

        /**
         * 绑定旧枚举任务。
         *
         * <p>这只给 Wathe 本体内置任务和旧扩展兼容使用；新增扩展任务不应该再申请旧枚举值。</p>
         */
        public @NotNull Builder legacyTask(@NotNull PlayerMoodComponent.Task legacyTask) {
            this.legacyTask = Objects.requireNonNull(legacyTask, "legacyTask");
            return this;
        }

        public @NotNull MoodTaskDefinition build() {
            return new MoodTaskDefinition(this);
        }
    }
}
