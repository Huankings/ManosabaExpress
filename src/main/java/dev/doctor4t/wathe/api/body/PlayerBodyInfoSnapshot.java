package dev.doctor4t.wathe.api.body;

import dev.doctor4t.wathe.api.Role;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一具玩家尸体在生成时冻结的验尸信息。
 *
 * <p>真实死亡和人工伪造尸体都通过同一种快照写入 Wathe，避免扩展继续各自维护
 * deathReason / playerRole CCA。{@code modifierSourceUuid} 只给 Harpy 这类扩展层使用：
 * 它表示“伪造词条数量参考谁”，不会改变尸体真实 owner 或显示名字。</p>
 */
public record PlayerBodyInfoSnapshot(@NotNull UUID ownerUuid,
                                     @NotNull String ownerName,
                                     @NotNull Identifier deathReason,
                                     @NotNull Identifier roleId,
                                     @Nullable UUID killerUuid,
                                     @Nullable String killerName,
                                     @Nullable Identifier killerRoleId,
                                     boolean synthetic,
                                     @Nullable UUID modifierSourceUuid) {
    public PlayerBodyInfoSnapshot {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(deathReason, "deathReason");
        Objects.requireNonNull(roleId, "roleId");
    }

    /**
     * 为在线目标创建人工尸体快照。伪尸体默认没有凶手；词条参考目标由扩展层按
     * {@code modifierSourceUuid} 生成符合伪造职业资格的随机假词条。
     */
    public static @NotNull PlayerBodyInfoSnapshot synthetic(@NotNull PlayerEntity owner,
                                                             @NotNull Identifier deathReason,
                                                             @NotNull Role fakeRole) {
        return new PlayerBodyInfoSnapshot(
                owner.getUuid(),
                owner.getGameProfile().getName(),
                deathReason,
                fakeRole.identifier(),
                null,
                null,
                null,
                true,
                owner.getUuid()
        );
    }
}
