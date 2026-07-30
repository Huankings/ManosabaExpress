package dev.doctor4t.wathe.api.death;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 统一击杀/死亡流程公开入口。
 *
 * <p>推荐优先级约定：</p>
 * <p>10000：重复死亡吞噬，例如已经进入时间狭缝的玩家再次被环境死亡扫到；</p>
 * <p>9000：特殊存活保护，例如双重人格休眠人格不应真正死亡；</p>
 * <p>8000：死亡流程状态标记，例如防递归处理中的死亡；</p>
 * <p>6000：回放临时上下文，例如魔术师播放代理身份；</p>
 * <p>1000：致死确认前的转化/拦截，例如活跃人格死亡转双活；</p>
 * <p>0：普通扩展逻辑；</p>
 * <p>-1000：确认死亡后的奖励或二段机制，例如赏金奖励、时间狭缝启动；</p>
 * <p>-9000：最终清理，例如死亡处理中标记复位。</p>
 */
public final class DeathApi {
    public static final int DEFAULT_PRIORITY = 0;
    public static final int PRIORITY_REPEATED_DEATH_GUARD = 10000;
    public static final int PRIORITY_SPECIAL_SURVIVAL_PROTECTION = 9000;
    public static final int PRIORITY_DEATH_PROCESS_STATE = 8000;
    public static final int PRIORITY_REPLAY_CONTEXT = 6000;
    public static final int PRIORITY_FATAL_INTERCEPT = 1000;
    public static final int PRIORITY_POST_CONFIRMED_DEATH = -1000;
    public static final int PRIORITY_FINAL_CLEANUP = -9000;

    private static final Comparator<PrioritizedInterceptor> INTERCEPTOR_COMPARATOR =
            Comparator.<PrioritizedInterceptor>comparingInt(PrioritizedInterceptor::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedInterceptor::order).reversed());
    private static final Comparator<PrioritizedDeathHandler> HANDLER_COMPARATOR =
            Comparator.<PrioritizedDeathHandler>comparingInt(PrioritizedDeathHandler::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedDeathHandler::order).reversed());
    private static final Comparator<PrioritizedRewardRule> REWARD_RULE_COMPARATOR =
            Comparator.<PrioritizedRewardRule>comparingInt(PrioritizedRewardRule::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedRewardRule::order).reversed());
    private static final Comparator<PrioritizedBodyHandler> BODY_HANDLER_COMPARATOR =
            Comparator.<PrioritizedBodyHandler>comparingInt(PrioritizedBodyHandler::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(PrioritizedBodyHandler::order).reversed());

    private static final List<PrioritizedInterceptor> EARLY_INTERCEPTORS = new ArrayList<>();
    private static final List<PrioritizedDeathHandler> BEFORE_ATTEMPT_HANDLERS = new ArrayList<>();
    private static final List<PrioritizedInterceptor> FATAL_INTERCEPTORS = new ArrayList<>();
    private static final List<PrioritizedDeathHandler> AFTER_MARKED_DEAD_HANDLERS = new ArrayList<>();
    private static final List<PrioritizedRewardRule> DEFAULT_KILLER_REWARD_RULES = new ArrayList<>();
    private static final List<PrioritizedDeathHandler> BEFORE_MOOD_RESET_HANDLERS = new ArrayList<>();
    private static final List<PrioritizedBodyHandler> BODY_SPAWN_HANDLERS = new ArrayList<>();
    private static final List<PrioritizedDeathHandler> AFTER_ATTEMPT_HANDLERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private DeathApi() {
    }

    /**
     * 注册最早期死亡拦截器。
     *
     * <p>这个阶段发生在 Wathe 标记“死亡流程开始”之前。适合吞掉重复死亡请求，
     * 例如已经进入时间狭缝的玩家再次被同一轮连锁死亡扫到。被这里取消的死亡不会进入
     * {@link #registerAfterAttempt(Identifier, int, DeathHandler)}，所以若扩展在外层已经写了临时状态，
     * 需要自己负责清理。</p>
     */
    public static void registerEarlyInterceptor(@NotNull Identifier id, int priority, @NotNull DeathInterceptor interceptor) {
        registerInterceptor(EARLY_INTERCEPTORS, INTERCEPTOR_COMPARATOR, id, priority, interceptor);
    }

    /**
     * 注册死亡尝试开始后的普通处理器。
     *
     * <p>这个阶段在 {@code AllowPlayerDeath}、疯魔护盾和真正切旁观之前执行。
     * 适合写“进入死亡处理中”标记、巫毒/附体这类递归连锁，或保留旧 mixin 在
     * {@code killPlayer} HEAD 执行的特殊语义。</p>
     */
    public static void registerBeforeAttempt(@NotNull Identifier id, int priority, @NotNull DeathHandler handler) {
        registerHandler(BEFORE_ATTEMPT_HANDLERS, HANDLER_COMPARATOR, id, priority, handler);
    }

    /**
     * 注册致死确认前的最终拦截器。
     *
     * <p>这个阶段发生在免死/护盾均未拦下之后、清除特殊存活授权和切旁观之前。
     * 适合把“这次致死”改写成另一种玩法结果，例如双重人格从活跃人格死亡转成双活解离。</p>
     */
    public static void registerFatalInterceptor(@NotNull Identifier id, int priority, @NotNull DeathInterceptor interceptor) {
        registerInterceptor(FATAL_INTERCEPTORS, INTERCEPTOR_COMPARATOR, id, priority, interceptor);
    }

    /**
     * 注册玩家已经被标记为死亡后的处理器。
     *
     * <p>此时 Wathe 已经清掉特殊存活授权并把玩家切到旁观，但还没有记录 death 回放、
     * 发放默认击杀收益、重置心情或生成尸体。适合炸弹携带者死亡清理这类必须看到“真实死亡已成立”
     * 但又要早于尸体/掉落发生的逻辑。</p>
     */
    public static void registerAfterMarkedDead(@NotNull Identifier id, int priority, @NotNull DeathHandler handler) {
        registerHandler(AFTER_MARKED_DEAD_HANDLERS, HANDLER_COMPARATOR, id, priority, handler);
    }

    /**
     * 注册 Wathe 默认击杀收益是否发放的决策规则。
     *
     * <p>这里只控制 Wathe 自己的 {@code MONEY_PER_KILL} / 任务币默认收益。
     * 职业额外奖励仍应放在 afterAttempt 并检查 {@link DeathContext#confirmedDeath()}，
     * 避免护盾、免死或致死转化也触发奖励。</p>
     */
    public static void registerDefaultKillerRewardRule(@NotNull Identifier id, int priority, @NotNull KillerRewardRule rule) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rule, "rule");
        synchronized (DEFAULT_KILLER_REWARD_RULES) {
            DEFAULT_KILLER_REWARD_RULES.removeIf(entry -> entry.id().equals(id));
            DEFAULT_KILLER_REWARD_RULES.add(new PrioritizedRewardRule(id, priority, nextOrder++, rule));
            DEFAULT_KILLER_REWARD_RULES.sort(REWARD_RULE_COMPARATOR);
        }
    }

    /**
     * 注册心情重置前处理器。
     *
     * <p>这个阶段适合依赖受害者死亡前组件状态、但又必须确认死亡成立后再执行的清理，
     * 例如天使守护关系解除、先知当前标记清理。</p>
     */
    public static void registerBeforeMoodReset(@NotNull Identifier id, int priority, @NotNull DeathHandler handler) {
        registerHandler(BEFORE_MOOD_RESET_HANDLERS, HANDLER_COMPARATOR, id, priority, handler);
    }

    /**
     * 注册尸体生成回调。
     *
     * <p>Wathe 会在 body 写好真实死者 UUID、外观、位置和朝向之后，spawn 到世界之前调用。
     * 扩展可以在这里写尸体 CCA、追加发光效果、登记隐藏尸体索引或保存验尸数据。</p>
     */
    public static void registerBodySpawn(@NotNull Identifier id, int priority, @NotNull BodySpawnHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (BODY_SPAWN_HANDLERS) {
            BODY_SPAWN_HANDLERS.removeIf(entry -> entry.id().equals(id));
            BODY_SPAWN_HANDLERS.add(new PrioritizedBodyHandler(id, priority, nextOrder++, handler));
            BODY_SPAWN_HANDLERS.sort(BODY_HANDLER_COMPARATOR);
        }
    }

    /**
     * 注册死亡尝试结束后的处理器。
     *
     * <p>只要死亡流程进入 {@link #registerBeforeAttempt(Identifier, int, DeathHandler)} 后，
     * 无论后续是免死、护盾、致死拦截、真实死亡还是中途 return，都会在 finally 中执行这里。
     * 因此它适合清理临时 ThreadLocal/状态标记，也适合在检查 {@link DeathContext#confirmedDeath()}
     * 后发放“确认击杀”奖励。</p>
     */
    public static void registerAfterAttempt(@NotNull Identifier id, int priority, @NotNull DeathHandler handler) {
        registerHandler(AFTER_ATTEMPT_HANDLERS, HANDLER_COMPARATOR, id, priority, handler);
    }

    public static @NotNull DeathDecision resolveEarlyInterceptors(@NotNull DeathContext context) {
        return resolveInterceptors(EARLY_INTERCEPTORS, context);
    }

    public static void invokeBeforeAttempt(@NotNull DeathContext context) {
        context.markBeforeAttemptStarted();
        invokeHandlers(BEFORE_ATTEMPT_HANDLERS, context);
    }

    public static @NotNull DeathDecision resolveFatalInterceptors(@NotNull DeathContext context) {
        return resolveInterceptors(FATAL_INTERCEPTORS, context);
    }

    public static void invokeAfterMarkedDead(@NotNull DeathContext context) {
        invokeHandlers(AFTER_MARKED_DEAD_HANDLERS, context);
    }

    public static boolean shouldGrantDefaultKillerReward(@NotNull DeathContext context, boolean defaultValue) {
        for (PrioritizedRewardRule entry : rewardRuleSnapshot()) {
            KillerRewardResult result = entry.rule().resolve(context, defaultValue);
            if (result == KillerRewardResult.ALLOW) {
                return true;
            }
            if (result == KillerRewardResult.DENY) {
                return false;
            }
        }
        return defaultValue;
    }

    public static void invokeBeforeMoodReset(@NotNull DeathContext context) {
        invokeHandlers(BEFORE_MOOD_RESET_HANDLERS, context);
    }

    public static void invokeBodySpawn(@NotNull BodySpawnContext context) {
        for (PrioritizedBodyHandler entry : bodyHandlerSnapshot()) {
            entry.handler().handle(context);
        }
    }

    public static void invokeAfterAttempt(@NotNull DeathContext context) {
        invokeHandlers(AFTER_ATTEMPT_HANDLERS, context);
    }

    private static void registerInterceptor(List<PrioritizedInterceptor> list,
                                            Comparator<PrioritizedInterceptor> comparator,
                                            Identifier id,
                                            int priority,
                                            DeathInterceptor interceptor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(interceptor, "interceptor");
        synchronized (list) {
            list.removeIf(entry -> entry.id().equals(id));
            list.add(new PrioritizedInterceptor(id, priority, nextOrder++, interceptor));
            list.sort(comparator);
        }
    }

    private static void registerHandler(List<PrioritizedDeathHandler> list,
                                        Comparator<PrioritizedDeathHandler> comparator,
                                        Identifier id,
                                        int priority,
                                        DeathHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (list) {
            list.removeIf(entry -> entry.id().equals(id));
            list.add(new PrioritizedDeathHandler(id, priority, nextOrder++, handler));
            list.sort(comparator);
        }
    }

    private static @NotNull DeathDecision resolveInterceptors(List<PrioritizedInterceptor> list, DeathContext context) {
        for (PrioritizedInterceptor entry : interceptorSnapshot(list)) {
            DeathDecision result = entry.interceptor().resolve(context);
            if (result != null && result != DeathDecision.PASS) {
                return result;
            }
        }
        return DeathDecision.PASS;
    }

    private static void invokeHandlers(List<PrioritizedDeathHandler> list, DeathContext context) {
        for (PrioritizedDeathHandler entry : handlerSnapshot(list)) {
            entry.handler().handle(context);
        }
    }

    private static List<PrioritizedInterceptor> interceptorSnapshot(List<PrioritizedInterceptor> list) {
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    private static List<PrioritizedDeathHandler> handlerSnapshot(List<PrioritizedDeathHandler> list) {
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    private static List<PrioritizedRewardRule> rewardRuleSnapshot() {
        synchronized (DEFAULT_KILLER_REWARD_RULES) {
            return List.copyOf(DEFAULT_KILLER_REWARD_RULES);
        }
    }

    private static List<PrioritizedBodyHandler> bodyHandlerSnapshot() {
        synchronized (BODY_SPAWN_HANDLERS) {
            return List.copyOf(BODY_SPAWN_HANDLERS);
        }
    }

    @FunctionalInterface
    public interface DeathInterceptor {
        @NotNull DeathDecision resolve(@NotNull DeathContext context);
    }

    @FunctionalInterface
    public interface DeathHandler {
        void handle(@NotNull DeathContext context);
    }

    @FunctionalInterface
    public interface KillerRewardRule {
        @NotNull KillerRewardResult resolve(@NotNull DeathContext context, boolean defaultValue);
    }

    @FunctionalInterface
    public interface BodySpawnHandler {
        void handle(@NotNull BodySpawnContext context);
    }

    private record PrioritizedInterceptor(@NotNull Identifier id,
                                          int priority,
                                          long order,
                                          @NotNull DeathInterceptor interceptor) {
    }

    private record PrioritizedDeathHandler(@NotNull Identifier id,
                                           int priority,
                                           long order,
                                           @NotNull DeathHandler handler) {
    }

    private record PrioritizedRewardRule(@NotNull Identifier id,
                                         int priority,
                                         long order,
                                         @NotNull KillerRewardRule rule) {
    }

    private record PrioritizedBodyHandler(@NotNull Identifier id,
                                          int priority,
                                          long order,
                                          @NotNull BodySpawnHandler handler) {
    }
}
