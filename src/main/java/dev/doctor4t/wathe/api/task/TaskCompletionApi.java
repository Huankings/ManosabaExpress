package dev.doctor4t.wathe.api.task;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.economy.EconomyApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.game.GameConstants;
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
 * <p>扩展职业可以继续通过 {@link #AFTER_TASK_COMPLETE} 接入任务完成后的特殊效果。
 * 旧版任务金币 provider 仍保留注册入口用于源码兼容；拥有杀手能力的玩家不会叠加旧任务金币 provider。
 * 任务币实验暂停期间，杀手任务币奖励常量为 0，所以默认也不会再发任务币。</p>
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
     * <p>这是多货币改造前留下的兼容入口：扩展源码里已有调用时仍能正常编译和启动。
     * 现在 Wathe 仍会在非杀手任务里沿用这些 provider 发金币；但只要玩家拥有杀手能力，
     * 就不会再叠加这些旧金币 provider，避免后续重新启用任务币时出现“双份任务收益”。</p>
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

        if (gameWorld.canUseKillerFeatures(player)) {
            /*
             * 任务币收益目前随杀手商店任务币交易一起暂停。
             * 常量保留在 GameConstants 中，后续重新启用任务币玩法时只需要把数值调回正数。
             * 这里加一层判断，避免 0 收益时还触发无意义同步。
             */
            if (GameConstants.TASK_MONEY_PER_KILLER_TASK > 0) {
                PlayerShopComponent.KEY.get(player).addCurrencyAmount(EconomyApi.TASK_MONEY, GameConstants.TASK_MONEY_PER_KILLER_TASK);
            }
        } else {
            int totalIncome = 0;
            for (PrioritizedTaskIncomeProvider entry : taskIncomeProviderSnapshot()) {
                totalIncome += Math.max(0, entry.provider().getTaskIncome(context));
            }

            if (totalIncome > 0) {
                PlayerShopComponent.KEY.get(player).addToBalance(totalIncome);
            }
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
