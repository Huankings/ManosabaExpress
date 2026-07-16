package dev.doctor4t.wathe.api.win;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次独立胜利的完整结算数据。
 *
 * <p>扩展模组只需要提供：
 * 1. 胜利 ID：用于自动推导翻译 key；
 * 2. 颜色：用于顶部公告和右侧独立阵营标题；
 * 3. 赢家 UUID：用于胜负音效、回放胜负标记和结算右侧分组。</p>
 *
 * <p>默认翻译 key 与现有 StupidExpress / KinsWathe 写法保持一致：
 * announcement.win.namespace.path、game.win.namespace.path、announcement.role.namespace.path。</p>
 */
public record CustomVictory(
        @NotNull Identifier id,
        @NotNull String announcementTranslationKey,
        @NotNull String detailTranslationKey,
        @NotNull String fallbackTitle,
        int color,
        @NotNull List<UUID> winnerUuids,
        @NotNull CustomVictoryGroup winnerGroup
) {
    public CustomVictory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(announcementTranslationKey, "announcementTranslationKey");
        Objects.requireNonNull(detailTranslationKey, "detailTranslationKey");
        Objects.requireNonNull(fallbackTitle, "fallbackTitle");
        Objects.requireNonNull(winnerUuids, "winnerUuids");
        Objects.requireNonNull(winnerGroup, "winnerGroup");
        winnerUuids = List.copyOf(winnerUuids);
    }

    public static @NotNull Builder builder(@NotNull Identifier id, int color) {
        return new Builder(id, color);
    }

    public static @NotNull CustomVictory of(
            @NotNull Identifier id,
            int color,
            @NotNull Collection<? extends PlayerEntity> winners
    ) {
        return builder(id, color).winnersFromPlayers(winners).build();
    }

    public boolean isWinner(UUID uuid) {
        return uuid != null && this.winnerUuids.contains(uuid);
    }

    public @NotNull NbtCompound writeToNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putString("id", this.id.toString());
        tag.putString("announcementTranslationKey", this.announcementTranslationKey);
        tag.putString("detailTranslationKey", this.detailTranslationKey);
        tag.putString("fallbackTitle", this.fallbackTitle);
        tag.putInt("color", this.color);

        net.minecraft.nbt.NbtList winners = new net.minecraft.nbt.NbtList();
        for (UUID uuid : this.winnerUuids) {
            winners.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("winners", winners);
        tag.put("winnerGroup", this.winnerGroup.writeToNbt());
        return tag;
    }

    public static @NotNull CustomVictory fromNbt(@NotNull NbtCompound tag) {
        Identifier id = Identifier.tryParse(tag.getString("id"));
        if (id == null) {
            id = Identifier.of("wathe", "custom");
        }

        List<UUID> winners = new java.util.ArrayList<>();
        for (net.minecraft.nbt.NbtElement element : tag.getList("winners", net.minecraft.nbt.NbtElement.INT_ARRAY_TYPE)) {
            winners.add(NbtHelper.toUuid(element));
        }

        return new CustomVictory(
                id,
                tag.getString("announcementTranslationKey"),
                tag.getString("detailTranslationKey"),
                tag.getString("fallbackTitle"),
                tag.getInt("color"),
                winners,
                CustomVictoryGroup.fromNbt(tag.getCompound("winnerGroup"))
        );
    }

    public static final class Builder {
        private final Identifier id;
        private final int color;
        private String announcementTranslationKey;
        private String detailTranslationKey;
        private String titleTranslationKey;
        private String fallbackTitle;
        private List<UUID> winnerUuids = List.of();

        private Builder(@NotNull Identifier id, int color) {
            this.id = Objects.requireNonNull(id, "id");
            this.color = color;
            this.announcementTranslationKey = "announcement.win." + id.getNamespace() + "." + id.getPath();
            this.detailTranslationKey = "game.win." + id.getNamespace() + "." + id.getPath();
            this.titleTranslationKey = CustomVictoryGroup.defaultTitleTranslationKey(id);
            this.fallbackTitle = CustomVictoryGroup.prettifyIdentifierPath(id.getPath());
        }

        public @NotNull Builder announcementTranslationKey(@NotNull String announcementTranslationKey) {
            this.announcementTranslationKey = Objects.requireNonNull(announcementTranslationKey, "announcementTranslationKey");
            return this;
        }

        public @NotNull Builder detailTranslationKey(@NotNull String detailTranslationKey) {
            this.detailTranslationKey = Objects.requireNonNull(detailTranslationKey, "detailTranslationKey");
            return this;
        }

        public @NotNull Builder titleTranslationKey(@NotNull String titleTranslationKey) {
            this.titleTranslationKey = Objects.requireNonNull(titleTranslationKey, "titleTranslationKey");
            return this;
        }

        public @NotNull Builder fallbackTitle(@NotNull String fallbackTitle) {
            this.fallbackTitle = Objects.requireNonNull(fallbackTitle, "fallbackTitle");
            return this;
        }

        public @NotNull Builder winners(@NotNull Collection<UUID> winnerUuids) {
            this.winnerUuids = List.copyOf(Objects.requireNonNull(winnerUuids, "winnerUuids"));
            return this;
        }

        public @NotNull Builder winnersFromPlayers(@NotNull Collection<? extends PlayerEntity> winners) {
            this.winnerUuids = CustomVictoryGroup.uuidsFromPlayers(winners);
            return this;
        }

        public @NotNull CustomVictory build() {
            CustomVictoryGroup group = new CustomVictoryGroup(
                    this.titleTranslationKey,
                    this.fallbackTitle,
                    this.color,
                    this.winnerUuids
            );
            return new CustomVictory(
                    this.id,
                    this.announcementTranslationKey,
                    this.detailTranslationKey,
                    this.fallbackTitle,
                    this.color,
                    this.winnerUuids,
                    group
            );
        }
    }
}
