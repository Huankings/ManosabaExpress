package dev.doctor4t.wathe.api.win;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 独立胜利结算页右侧的“独立胜利阵营”分组。
 *
 * <p>这里刻意只保存 UUID，而不是保存 Player 实体：
 * 结算动画、TAB 结算页和回放胜负标记都可能在玩家离线之后继续读取。
 * 如果保存实体，玩家一退出就会丢失胜利归属；保存 UUID 则能稳定还原。</p>
 */
public record CustomVictoryGroup(
        @NotNull String titleTranslationKey,
        @NotNull String fallbackTitle,
        int color,
        @NotNull List<UUID> playerUuids
) {
    public CustomVictoryGroup {
        Objects.requireNonNull(titleTranslationKey, "titleTranslationKey");
        Objects.requireNonNull(fallbackTitle, "fallbackTitle");
        Objects.requireNonNull(playerUuids, "playerUuids");
        playerUuids = List.copyOf(playerUuids);
    }

    public static @NotNull CustomVictoryGroup of(
            @NotNull Identifier id,
            int color,
            @NotNull Collection<UUID> playerUuids
    ) {
        return new CustomVictoryGroup(
                defaultTitleTranslationKey(id),
                prettifyIdentifierPath(id.getPath()),
                color,
                List.copyOf(playerUuids)
        );
    }

    public boolean contains(UUID uuid) {
        return uuid != null && this.playerUuids.contains(uuid);
    }

    public @NotNull NbtCompound writeToNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putString("titleTranslationKey", this.titleTranslationKey);
        tag.putString("fallbackTitle", this.fallbackTitle);
        tag.putInt("color", this.color);

        NbtList players = new NbtList();
        for (UUID uuid : this.playerUuids) {
            players.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("players", players);
        return tag;
    }

    public static @NotNull CustomVictoryGroup fromNbt(@NotNull NbtCompound tag) {
        List<UUID> players = new ArrayList<>();
        for (NbtElement element : tag.getList("players", NbtElement.INT_ARRAY_TYPE)) {
            players.add(NbtHelper.toUuid(element));
        }
        return new CustomVictoryGroup(
                tag.getString("titleTranslationKey"),
                tag.getString("fallbackTitle"),
                tag.getInt("color"),
                players
        );
    }

    static @NotNull String defaultTitleTranslationKey(@NotNull Identifier id) {
        return "announcement.role." + id.getNamespace() + "." + id.getPath();
    }

    static @NotNull List<UUID> uuidsFromPlayers(@NotNull Collection<? extends PlayerEntity> players) {
        List<UUID> uuids = new ArrayList<>();
        for (PlayerEntity player : players) {
            if (player != null) {
                uuids.add(player.getUuid());
            }
        }
        return uuids;
    }

    static @NotNull String prettifyIdentifierPath(@NotNull String path) {
        String[] parts = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? path : builder.toString();
    }
}
