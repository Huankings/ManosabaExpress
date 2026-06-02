package dev.doctor4t.wathe.api.event;

import dev.doctor4t.wathe.record.GameRecordManager;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.world.ServerWorld;

import static net.fabricmc.fabric.api.event.EventFactory.createArrayBacked;

/**
 * 回放系统对外暴露的扩展事件。
 *
 * <p>后续扩展职业模组如果需要在对局结束后读取完整记录，
 * 可以通过这里拿到最后一局的 MatchRecord。</p>
 */
public final class RecordEvents {
    private RecordEvents() {
    }

    /**
     * 一局对局完整结束、记录封存完成后触发。
     */
    public static final Event<OnRecordEnd> ON_RECORD_END = createArrayBacked(OnRecordEnd.class, listeners -> (world, record) -> {
        for (OnRecordEnd listener : listeners) {
            listener.onRecordEnd(world, record);
        }
    });

    @FunctionalInterface
    public interface OnRecordEnd {
        void onRecordEnd(ServerWorld world, GameRecordManager.MatchRecord record);
    }
}
