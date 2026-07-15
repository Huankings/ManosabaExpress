package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

/**
 * Wathe 任务真正完成后的公开接入点。
 *
 * <p>这里和 {@code PlayerMoodComponent#setMood(float)} 不同：
 * 任务完成事件只会在某个任务实际达成并从任务栏移除时触发，
 * 不会被普通心情变化、外部治疗、调试命令等非任务来源误触发。</p>
 *
 * <p>扩展职业可以用两种方式接入：</p>
 * <p>1. 注册任务金币 provider，让 Wathe 自动累计并同步金币；</p>
 * <p>2. 监听 {@link #AFTER_TASK_COMPLETE}，处理冷却缩减、护盾进度等非金币效果。</p>
 */
public final class TaskCompletionApi {
    public static final int DEFAULT_PRIORITY = 0;

    public static final Event<AfterTaskComplete> AFTER_TASK_COMPLETE = createArrayBacked(
            AfterTaskComplete.class,
            listeners -> context -> {
                for (AfterTaskComplete listener : listeners) {
                    listener.afterTaskComplete(context);
                }
            }
    );

    private static final Comparator<PrioritizedTaskIncomeProvider> TASK_INCOME_COMPARATOR =
            Comparator.<PrioritizedTaskIncomeProvider>comparingInt(PrioritizedTaskIncomeProvider::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedTaskIncomeProvider::order).reversed());
    private static final List<PrioritizedTaskIncomeProvider> TASK_INCOME_PROVIDERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private TaskCompletionApi() {
    }

    /**
     * 注册任务金币收入。
     *
     * <p>provider 返回的是“本次任务完成要额外发放多少金币”。
     * Wathe 会把所有 provider 的结果相加，并只调用一次 {@link PlayerShopComponent#addToBalance(int)}，
     * 避免多个扩展叠加时重复同步玩家金币组件。</p>
     */
    public static synchronized void registerTaskIncomeProvider(
            @NotNull Identifier id,
            int priority,
            @NotNull TaskIncomeProvider provider
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        TASK_INCOME_PROVIDERS.removeIf(entry -> entry.id().equals(id));
        TASK_INCOME_PROVIDERS.add(new PrioritizedTaskIncomeProvider(id, priority, nextOrder++, provider));
        TASK_INCOME_PROVIDERS.sort(TASK_INCOME_COMPARATOR);
    }

    /**
     * Wathe 本体在任务完成点调用的统一分发方法。
     *
     * <p>扩展模组不应该直接调用这个方法；它是给
     * {@code PlayerMoodComponent#completeTask(...)} 这样的本体入口使用的。</p>
     */
    public static void handleTaskCompleted(
            @NotNull ServerPlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @NotNull PlayerMoodComponent.Task task,
            boolean rewardedMood
    ) {
        Role role = gameWorld.getRole(player);
        TaskCompletionContext context = new TaskCompletionContext(player, gameWorld, role, task, rewardedMood);

        int totalIncome = 0;
        for (PrioritizedTaskIncomeProvider entry : taskIncomeProviderSnapshot()) {
            totalIncome += Math.max(0, entry.provider().getTaskIncome(context));
        }

        if (totalIncome > 0) {
            PlayerShopComponent.KEY.get(player).addToBalance(totalIncome);
        }

        AFTER_TASK_COMPLETE.invoker().afterTaskComplete(context);
    }

    private static synchronized List<PrioritizedTaskIncomeProvider> taskIncomeProviderSnapshot() {
        return List.copyOf(TASK_INCOME_PROVIDERS);
    }

    @FunctionalInterface
    public interface TaskIncomeProvider {
        int getTaskIncome(@NotNull TaskCompletionContext context);
    }

    @FunctionalInterface
    public interface AfterTaskComplete {
        void afterTaskComplete(@NotNull TaskCompletionContext context);
    }

    public record TaskCompletionContext(
            @NotNull ServerPlayerEntity player,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role role,
            @NotNull PlayerMoodComponent.Task task,
            boolean rewardedMood
    ) {
    }

    private record PrioritizedTaskIncomeProvider(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull TaskIncomeProvider provider
    ) {
    }
}
