package dev.doctor4t.wathe.record.replay;

import dev.doctor4t.wathe.record.GameRecordEvent;
import dev.doctor4t.wathe.record.GameRecordManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * 把单条事件格式化成最终聊天文本。
 */
@FunctionalInterface
public interface ReplayEventFormatter {
    @Nullable
    Text format(GameRecordEvent event, GameRecordManager.MatchRecord match, ServerWorld world);
}
