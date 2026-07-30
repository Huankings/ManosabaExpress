package dev.doctor4t.wathe.api.death;

import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 新尸体实体生成后的公开上下文。
 *
 * <p>这个阶段尸体已经写入真实死者 UUID、外观 UUID、位置和朝向，
 * 但还没有被放进世界。扩展可以安全写 CCA、状态效果或世界索引。</p>
 */
public record BodySpawnContext(@NotNull DeathContext deathContext,
                               @NotNull PlayerBodyEntity body,
                               @NotNull PlayerEntity victim,
                               @Nullable PlayerEntity killer,
                               @NotNull Identifier deathReason) {
}
