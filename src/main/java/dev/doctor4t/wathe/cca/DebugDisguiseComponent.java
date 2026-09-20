package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务器管理员调试用的全局变形状态。
 *
 * <p>这里故意使用 scoreboard component，而不是某个世界组件或静态 Map：
 * scoreboard 在整台服务器范围内只有一份，且会随存档保存，所以玩家换地图、
 * 离线重连甚至服务器重启后，仍然能得到同一份变形规则。</p>
 *
 * <p>该组件只保存“谁应显示成哪个 UUID”和目标名称快照。真正的皮肤贴图仍由
 * 客户端 SkinProvider 解析，避免服务端加载客户端渲染类。</p>
 */
public final class DebugDisguiseComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<DebugDisguiseComponent> KEY =
            ComponentRegistry.getOrCreate(Wathe.id("debug_disguise"), DebugDisguiseComponent.class);

    /** 结束时间使用 Unix 毫秒；0 表示永久。这样离线期间和服务器停机期间也会继续计时。 */
    private static final long PERMANENT = 0L;

    private final Scoreboard scoreboard;

    @Nullable
    private DisguiseEntry globalEntry;
    private final Map<UUID, DisguiseEntry> playerEntries = new HashMap<>();
    /** 全局规则存在时，clear 命令用这个集合表达“明确排除全局规则”。 */
    private final Set<UUID> globalExclusions = new HashSet<>();

    public DebugDisguiseComponent(@NotNull Scoreboard scoreboard, @Nullable MinecraftServer server) {
        this.scoreboard = scoreboard;
    }

    public void sync() {
        KEY.sync(this.scoreboard);
    }

    @Nullable
    public DisguiseEntry getGlobalEntry() {
        return this.globalEntry;
    }

    @Nullable
    public DisguiseEntry getPlayerEntry(@NotNull UUID playerUuid) {
        return this.playerEntries.get(playerUuid);
    }

    public boolean isExcludedFromGlobal(@NotNull UUID playerUuid) {
        return this.globalExclusions.contains(playerUuid);
    }

    /**
     * 服务端和客户端都可调用的有效性判断。客户端会在服务端下一次同步前自行隐藏已过期规则，
     * 服务端则在 tick 中删除规则并广播一次变更。
     */
    public static boolean isActive(@Nullable DisguiseEntry entry, long nowMillis) {
        return entry != null && (entry.expiresAtMillis() == PERMANENT || entry.expiresAtMillis() > nowMillis);
    }

    public void setPlayerEntries(@NotNull Iterable<UUID> players,
                                 @NotNull UUID appearanceUuid,
                                 @NotNull String appearanceName,
                                 long expiresAtMillis) {
        for (UUID playerUuid : players) {
            this.playerEntries.put(playerUuid, new DisguiseEntry(appearanceUuid, appearanceName, expiresAtMillis));
            // 显式设置优先于全局规则，同时取消此前的“排除全局”标记。
            this.globalExclusions.remove(playerUuid);
        }
        this.sync();
    }

    public void setGlobal(@NotNull UUID appearanceUuid, @NotNull String appearanceName, long expiresAtMillis) {
        this.globalEntry = new DisguiseEntry(appearanceUuid, appearanceName, expiresAtMillis);
        // “所有人”语义必须真的覆盖所有人，重新设置时清理旧的例外名单。
        this.globalExclusions.clear();
        this.sync();
    }

    public int clearPlayers(@NotNull Iterable<UUID> players) {
        int changed = 0;
        for (UUID playerUuid : players) {
            boolean removed = this.playerEntries.remove(playerUuid) != null;
            if (this.globalEntry != null) {
                removed |= this.globalExclusions.add(playerUuid);
            } else {
                removed |= this.globalExclusions.remove(playerUuid);
            }
            if (removed) {
                changed++;
            }
        }
        if (changed > 0) {
            this.sync();
        }
        return changed;
    }

    public void clearAll() {
        this.globalEntry = null;
        this.playerEntries.clear();
        this.globalExclusions.clear();
        this.sync();
    }

    /** 返回当前记录数量，供管理员反馈使用。 */
    public int getPlayerEntryCount() {
        return this.playerEntries.size();
    }

    public Map<UUID, DisguiseEntry> getPlayerEntriesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(this.playerEntries));
    }

    public int getGlobalExclusionCount() {
        return this.globalExclusions.size();
    }

    /** 只在服务端 tick 调用，避免客户端组件修改持久化状态。 */
    @Override
    public void serverTick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (this.globalEntry != null && !isActive(this.globalEntry, now)) {
            this.globalEntry = null;
            this.globalExclusions.clear();
            changed = true;
        }
        var iterator = this.playerEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!isActive(iterator.next().getValue(), now)) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) {
            this.sync();
        }
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.globalEntry = tag.contains("Global") ? DisguiseEntry.fromNbt(tag.getCompound("Global")) : null;
        this.playerEntries.clear();
        for (NbtElement element : tag.getList("Players", NbtElement.COMPOUND_TYPE)) {
            NbtCompound playerTag = (NbtCompound) element;
            if (playerTag.contains("PlayerUuid")) {
                DisguiseEntry entry = DisguiseEntry.fromNbt(playerTag);
                if (entry != null) {
                    this.playerEntries.put(playerTag.getUuid("PlayerUuid"), entry);
                }
            }
        }
        this.globalExclusions.clear();
        for (NbtElement element : tag.getList("GlobalExclusions", NbtElement.INT_ARRAY_TYPE)) {
            UUID uuid = NbtHelper.toUuid(element);
            if (uuid != null) {
                this.globalExclusions.add(uuid);
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        if (this.globalEntry != null) {
            tag.put("Global", this.globalEntry.toNbt());
        }
        NbtList players = new NbtList();
        for (Map.Entry<UUID, DisguiseEntry> entry : this.playerEntries.entrySet()) {
            NbtCompound playerTag = entry.getValue().toNbt();
            playerTag.putUuid("PlayerUuid", entry.getKey());
            players.add(playerTag);
        }
        tag.put("Players", players);
        NbtList exclusions = new NbtList();
        for (UUID uuid : this.globalExclusions) {
            exclusions.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("GlobalExclusions", exclusions);
    }

    public record DisguiseEntry(@NotNull UUID appearanceUuid, @NotNull String appearanceName, long expiresAtMillis) {
        private NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("AppearanceUuid", this.appearanceUuid);
            tag.putString("AppearanceName", this.appearanceName);
            tag.putLong("ExpiresAtMillis", this.expiresAtMillis);
            return tag;
        }

        @Nullable
        private static DisguiseEntry fromNbt(@NotNull NbtCompound tag) {
            if (!tag.contains("AppearanceUuid")) {
                return null;
            }
            return new DisguiseEntry(
                    tag.getUuid("AppearanceUuid"),
                    tag.getString("AppearanceName"),
                    Math.max(PERMANENT, tag.getLong("ExpiresAtMillis"))
            );
        }
    }

}
