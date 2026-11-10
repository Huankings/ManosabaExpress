package dev.doctor4t.wathe.api.body;

import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Wathe 完成尸体基础信息写入后的扩展上下文。
 *
 * <p>HarpyModLoader 可在这里冻结词条快照；其它扩展也可以登记只属于自己的尸体数据。
 * handler 在尸体 spawn 前执行，因此适合写实体 CCA，但不应在这里再次生成尸体。</p>
 */
public record BodyInfoInitializedContext(@NotNull PlayerBodyEntity body,
                                         @NotNull PlayerBodyInfoSnapshot snapshot) {
}
