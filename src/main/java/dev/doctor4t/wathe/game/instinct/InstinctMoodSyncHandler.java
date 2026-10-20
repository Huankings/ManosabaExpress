package dev.doctor4t.wathe.game.instinct;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.sync.PlayerComponentSyncApi;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.game.GameFunctions;

/**
 * 原生本能心情色所需的服务端观察者同步规则。
 *
 * <p>WatheClient 的默认本能高亮会读取无辜目标的 getMood()，再按心情阈值选择颜色。
 * 服务端能读取目标组件并不代表杀手客户端也能读取；若心情只同步给本人，杀手看到的目标
 * 心情始终是默认满值 1，所有无辜目标就都会保持绿色。</p>
 */
public final class InstinctMoodSyncHandler {
    private static boolean initialized;

    private InstinctMoodSyncHandler() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        PlayerComponentSyncApi.registerObserverRule(
                Wathe.id("sync/instinct_mood_observers"),
                PlayerMoodComponent.KEY,
                PlayerComponentSyncApi.DEFAULT_PRIORITY,
                context -> {
                    GameWorldComponent game = GameWorldComponent.KEY.get(context.owner().getWorld());
                    if (!game.isRunning()
                            || !GameFunctions.isPlayerAliveAndSurvival(context.owner())
                            || !game.isInnocent(context.owner())) {
                        return false;
                    }

                    /*
                     * 与 WatheClient 默认本能的资格和分支保持一致：
                     * 1. 原生或扩展的杀手能力职业按 canUseKillerFeatures 判断，不能只认原版 KILLER；
                     * 2. 旁观/创造视角也会使用无辜目标的心情色，特殊存活旁观仍按存活规则判断；
                     * 3. 杀手观察杀手走固定红色分支，即使目标同时标为 innocent 也无需读其心情。
                     * 本能键属于客户端输入，不在服务端读取；这里授权“有资格的观察者”，
                     * 是否真的显示描边仍由客户端 InstinctApi、按键和可见性规则决定。
                     */
                    if (game.canUseKillerFeatures(context.recipient())
                            && GameFunctions.isPlayerAliveAndSurvival(context.recipient())) {
                        return !game.canUseKillerFeatures(context.owner());
                    }
                    return GameFunctions.isPlayerSpectatingOrCreative(context.recipient());
                }
        );
        /*
         * 不新增 tick 发包、独立 UUID 缓存或玩法状态：目标心情变化继续走组件原有 sync 与 5 tick
         * 校准；追踪开始由 CCA 补发；转职、回溯、模式切换等权限变化由通用 API 每 20 tick 检查，
         * 新增权限时补发，撤权时清理一次缓存。同步关系不属于时停者需要回溯的玩法数据。
         */
    }
}
