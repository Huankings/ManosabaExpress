package dev.doctor4t.wathe.api.event;

import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

public final class GameEvents {

    private GameEvents() {
    }

    /**
     * 新局初始化真正开始前的服务端世界生命周期入口。
     *
     * <p>它早于 Wathe 清背包、分配职业以及扩展模式初始化，专门用于清理上一局仍保存在
     * 世界组件中的配对、倒计时等数据。需要在分配完成后读取最终职业的逻辑仍应使用
     * {@link #ON_FINISH_INITIALIZE}，不要把两个时序混用。</p>
     */
    public static final Event<OnBeginInitialize> ON_BEGIN_INITIALIZE = createArrayBacked(OnBeginInitialize.class, listeners -> (world, gameWorldComponent) -> {
        for (OnBeginInitialize listener : listeners) {
            listener.onBeginInitialize(world, gameWorldComponent);
        }
    });

    public static final Event<OnGameStart> ON_GAME_START = createArrayBacked(OnGameStart.class, listeners -> (gameMode) -> {
        for (OnGameStart listener : listeners) {
            listener.onGameStart(gameMode);
        }
    });

    public static final Event<OnGameStop> ON_GAME_STOP = createArrayBacked(OnGameStop.class, listeners -> (gameMode) -> {
        for (OnGameStop listener : listeners) {
            listener.onGameStop(gameMode);
        }
    });

    public static final Event<OnFinishInitialize> ON_FINISH_INITIALIZE = createArrayBacked(OnFinishInitialize.class, listeners -> (world, gameWorldComponent) -> {
        for (OnFinishInitialize listener : listeners) {
            listener.onFinishInitialize(world, gameWorldComponent);
        }
    });

    public static final Event<OnFinishFinalize> ON_FINISH_FINALIZE = createArrayBacked(OnFinishFinalize.class, listeners -> (world, gameWorldComponent) -> {
        for (OnFinishFinalize listener : listeners) {
            listener.onFinishFinalize(world, gameWorldComponent);
        }
    });

    public interface OnGameStart {
        void onGameStart(GameMode gameMode);
    }

    public interface OnBeginInitialize {
        void onBeginInitialize(ServerWorld world, GameWorldComponent gameComponent);
    }

    public interface OnGameStop {
        void onGameStop(GameMode gameMode);
    }

    public interface OnFinishInitialize {
        void onFinishInitialize(World world, GameWorldComponent gameComponent);
    }

    public interface OnFinishFinalize {
        void onFinishFinalize(World world, GameWorldComponent gameComponent);
    }
}
