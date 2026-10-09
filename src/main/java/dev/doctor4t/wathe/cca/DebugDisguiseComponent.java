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

    @Nullable
    private DisguiseEntry bodyGlobalEntry;
    private final Map<UUID, DisguiseEntry> bodyEntries = new HashMap<>();
    /** 尸体全局规则的单独排除名单，和玩家本体规则互不影响。 */
    private final Set<UUID> bodyGlobalExclusions = new HashSet<>();

    @Nullable
    private DisguiseEntry identityGlobalEntry;
    private final Map<UUID, DisguiseEntry> identityEntries = new HashMap<>();
    private final Set<UUID> identityGlobalExclusions = new HashSet<>();

    @Nullable
    private DisguiseEntry chatIdentityGlobalEntry;
    private final Map<UUID, DisguiseEntry> chatIdentityEntries = new HashMap<>();
    private final Set<UUID> chatIdentityGlobalExclusions = new HashSet<>();

    @Nullable
    private DisguiseEntry replayIdentityGlobalEntry;
    private final Map<UUID, DisguiseEntry> replayIdentityEntries = new HashMap<>();
    private final Set<UUID> replayIdentityGlobalExclusions = new HashSet<>();

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

    @Nullable
    public DisguiseEntry getBodyGlobalEntry() {
        return this.bodyGlobalEntry;
    }

    @Nullable
    public DisguiseEntry getBodyEntry(@NotNull UUID ownerUuid) {
        return this.bodyEntries.get(ownerUuid);
    }

    public boolean isBodyExcludedFromGlobal(@NotNull UUID ownerUuid) {
        return this.bodyGlobalExclusions.contains(ownerUuid);
    }

    public void setBodyEntries(@NotNull Iterable<UUID> owners,
                               @NotNull UUID appearanceUuid,
                               @NotNull String appearanceName,
                               long expiresAtMillis) {
        for (UUID ownerUuid : owners) {
            this.bodyEntries.put(ownerUuid, new DisguiseEntry(appearanceUuid, appearanceName, expiresAtMillis));
            this.bodyGlobalExclusions.remove(ownerUuid);
        }
        this.sync();
    }

    public void setBodyGlobal(@NotNull UUID appearanceUuid, @NotNull String appearanceName, long expiresAtMillis) {
        this.bodyGlobalEntry = new DisguiseEntry(appearanceUuid, appearanceName, expiresAtMillis);
        this.bodyGlobalExclusions.clear();
        this.sync();
    }

    public int clearBodyEntries(@NotNull Iterable<UUID> owners) {
        int changed = 0;
        for (UUID ownerUuid : owners) {
            boolean removed = this.bodyEntries.remove(ownerUuid) != null;
            if (this.bodyGlobalEntry != null) {
                removed |= this.bodyGlobalExclusions.add(ownerUuid);
            } else {
                removed |= this.bodyGlobalExclusions.remove(ownerUuid);
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

    public void clearBodyAll() {
        this.bodyGlobalEntry = null;
        this.bodyEntries.clear();
        this.bodyGlobalExclusions.clear();
        this.sync();
    }

    public Map<UUID, DisguiseEntry> getBodyEntriesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(this.bodyEntries));
    }

    public int getBodyGlobalExclusionCount() {
        return this.bodyGlobalExclusions.size();
    }

    @Nullable
    public DisguiseEntry getIdentityGlobalEntry() {
        return this.identityGlobalEntry;
    }

    @Nullable
    public DisguiseEntry getIdentityEntry(@NotNull UUID playerUuid) {
        return this.identityEntries.get(playerUuid);
    }

    public boolean isIdentityExcludedFromGlobal(@NotNull UUID playerUuid) {
        return this.identityGlobalExclusions.contains(playerUuid);
    }

    public void setIdentityEntries(@NotNull Iterable<UUID> players,
                                   @NotNull UUID identityUuid,
                                   @NotNull String identityName,
                                   long expiresAtMillis) {
        for (UUID playerUuid : players) {
            this.identityEntries.put(playerUuid, new DisguiseEntry(identityUuid, identityName, expiresAtMillis));
            this.identityGlobalExclusions.remove(playerUuid);
        }
        this.sync();
    }

    public void setIdentityGlobal(@NotNull UUID identityUuid, @NotNull String identityName, long expiresAtMillis) {
        this.identityGlobalEntry = new DisguiseEntry(identityUuid, identityName, expiresAtMillis);
        this.identityGlobalExclusions.clear();
        this.sync();
    }

    public int clearIdentityEntries(@NotNull Iterable<UUID> players) {
        int changed = 0;
        for (UUID playerUuid : players) {
            boolean removed = this.identityEntries.remove(playerUuid) != null;
            if (this.identityGlobalEntry != null) {
                removed |= this.identityGlobalExclusions.add(playerUuid);
            } else {
                removed |= this.identityGlobalExclusions.remove(playerUuid);
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

    public void clearIdentityAll() {
        this.identityGlobalEntry = null;
        this.identityEntries.clear();
        this.identityGlobalExclusions.clear();
        this.sync();
    }

    public Map<UUID, DisguiseEntry> getIdentityEntriesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(this.identityEntries));
    }

    public int getIdentityGlobalExclusionCount() {
        return this.identityGlobalExclusions.size();
    }

    @Nullable
    public DisguiseEntry getChatIdentityGlobalEntry() { return this.chatIdentityGlobalEntry; }

    @Nullable
    public DisguiseEntry getChatIdentityEntry(@NotNull UUID playerUuid) { return this.chatIdentityEntries.get(playerUuid); }

    public boolean isChatIdentityExcludedFromGlobal(@NotNull UUID playerUuid) {
        return this.chatIdentityGlobalExclusions.contains(playerUuid);
    }

    public void setChatIdentityEntries(@NotNull Iterable<UUID> players, @NotNull UUID identityUuid,
                                       @NotNull String identityName, long expiresAtMillis) {
        for (UUID playerUuid : players) {
            this.chatIdentityEntries.put(playerUuid, new DisguiseEntry(identityUuid, identityName, expiresAtMillis));
            this.chatIdentityGlobalExclusions.remove(playerUuid);
        }
        this.sync();
    }

    public void setChatIdentityGlobal(@NotNull UUID identityUuid, @NotNull String identityName, long expiresAtMillis) {
        this.chatIdentityGlobalEntry = new DisguiseEntry(identityUuid, identityName, expiresAtMillis);
        this.chatIdentityGlobalExclusions.clear();
        this.sync();
    }

    public int clearChatIdentityEntries(@NotNull Iterable<UUID> players) {
        int changed = 0;
        for (UUID playerUuid : players) {
            boolean removed = this.chatIdentityEntries.remove(playerUuid) != null;
            if (this.chatIdentityGlobalEntry != null) {
                removed |= this.chatIdentityGlobalExclusions.add(playerUuid);
            } else {
                removed |= this.chatIdentityGlobalExclusions.remove(playerUuid);
            }
            if (removed) changed++;
        }
        if (changed > 0) this.sync();
        return changed;
    }

    public void clearChatIdentityAll() {
        this.chatIdentityGlobalEntry = null;
        this.chatIdentityEntries.clear();
        this.chatIdentityGlobalExclusions.clear();
        this.sync();
    }

    public Map<UUID, DisguiseEntry> getChatIdentityEntriesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(this.chatIdentityEntries));
    }

    public int getChatIdentityGlobalExclusionCount() { return this.chatIdentityGlobalExclusions.size(); }

    @Nullable
    public DisguiseEntry getReplayIdentityGlobalEntry() {
        return this.replayIdentityGlobalEntry;
    }

    @Nullable
    public DisguiseEntry getReplayIdentityEntry(@NotNull UUID playerUuid) {
        return this.replayIdentityEntries.get(playerUuid);
    }

    public boolean isReplayIdentityExcludedFromGlobal(@NotNull UUID playerUuid) {
        return this.replayIdentityGlobalExclusions.contains(playerUuid);
    }

    public void setReplayIdentityEntries(@NotNull Iterable<UUID> players,
                                         @NotNull UUID identityUuid,
                                         @NotNull String identityName,
                                         long expiresAtMillis) {
        for (UUID playerUuid : players) {
            this.replayIdentityEntries.put(playerUuid, new DisguiseEntry(identityUuid, identityName, expiresAtMillis));
            this.replayIdentityGlobalExclusions.remove(playerUuid);
        }
        this.sync();
    }

    public void setReplayIdentityGlobal(@NotNull UUID identityUuid, @NotNull String identityName, long expiresAtMillis) {
        this.replayIdentityGlobalEntry = new DisguiseEntry(identityUuid, identityName, expiresAtMillis);
        this.replayIdentityGlobalExclusions.clear();
        this.sync();
    }

    public int clearReplayIdentityEntries(@NotNull Iterable<UUID> players) {
        int changed = 0;
        for (UUID playerUuid : players) {
            boolean removed = this.replayIdentityEntries.remove(playerUuid) != null;
            if (this.replayIdentityGlobalEntry != null) {
                removed |= this.replayIdentityGlobalExclusions.add(playerUuid);
            } else {
                removed |= this.replayIdentityGlobalExclusions.remove(playerUuid);
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

    public void clearReplayIdentityAll() {
        this.replayIdentityGlobalEntry = null;
        this.replayIdentityEntries.clear();
        this.replayIdentityGlobalExclusions.clear();
        this.sync();
    }

    public Map<UUID, DisguiseEntry> getReplayIdentityEntriesSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(this.replayIdentityEntries));
    }

    public int getReplayIdentityGlobalExclusionCount() {
        return this.replayIdentityGlobalExclusions.size();
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
        if (this.bodyGlobalEntry != null && !isActive(this.bodyGlobalEntry, now)) {
            this.bodyGlobalEntry = null;
            this.bodyGlobalExclusions.clear();
            changed = true;
        }
        if (this.identityGlobalEntry != null && !isActive(this.identityGlobalEntry, now)) {
            this.identityGlobalEntry = null;
            this.identityGlobalExclusions.clear();
            changed = true;
        }
        if (this.chatIdentityGlobalEntry != null && !isActive(this.chatIdentityGlobalEntry, now)) {
            this.chatIdentityGlobalEntry = null;
            this.chatIdentityGlobalExclusions.clear();
            changed = true;
        }
        if (this.replayIdentityGlobalEntry != null && !isActive(this.replayIdentityGlobalEntry, now)) {
            this.replayIdentityGlobalEntry = null;
            this.replayIdentityGlobalExclusions.clear();
            changed = true;
        }
        var iterator = this.playerEntries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!isActive(iterator.next().getValue(), now)) {
                iterator.remove();
                changed = true;
            }
        }
        var bodyIterator = this.bodyEntries.entrySet().iterator();
        while (bodyIterator.hasNext()) {
            if (!isActive(bodyIterator.next().getValue(), now)) {
                bodyIterator.remove();
                changed = true;
            }
        }
        var identityIterator = this.identityEntries.entrySet().iterator();
        while (identityIterator.hasNext()) {
            if (!isActive(identityIterator.next().getValue(), now)) {
                identityIterator.remove();
                changed = true;
            }
        }
        var chatIterator = this.chatIdentityEntries.entrySet().iterator();
        while (chatIterator.hasNext()) {
            if (!isActive(chatIterator.next().getValue(), now)) {
                chatIterator.remove();
                changed = true;
            }
        }
        var replayIdentityIterator = this.replayIdentityEntries.entrySet().iterator();
        while (replayIdentityIterator.hasNext()) {
            if (!isActive(replayIdentityIterator.next().getValue(), now)) {
                replayIdentityIterator.remove();
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
        this.bodyGlobalEntry = tag.contains("BodyGlobal") ? DisguiseEntry.fromNbt(tag.getCompound("BodyGlobal")) : null;
        this.identityGlobalEntry = tag.contains("IdentityGlobal") ? DisguiseEntry.fromNbt(tag.getCompound("IdentityGlobal")) : null;
        this.chatIdentityGlobalEntry = tag.contains("ChatIdentityGlobal") ? DisguiseEntry.fromNbt(tag.getCompound("ChatIdentityGlobal")) : null;
        this.replayIdentityGlobalEntry = tag.contains("ReplayIdentityGlobal") ? DisguiseEntry.fromNbt(tag.getCompound("ReplayIdentityGlobal")) : null;
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
        this.bodyEntries.clear();
        for (NbtElement element : tag.getList("Bodies", NbtElement.COMPOUND_TYPE)) {
            NbtCompound bodyTag = (NbtCompound) element;
            if (bodyTag.contains("OwnerUuid")) {
                DisguiseEntry entry = DisguiseEntry.fromNbt(bodyTag);
                if (entry != null) {
                    this.bodyEntries.put(bodyTag.getUuid("OwnerUuid"), entry);
                }
            }
        }
        this.bodyGlobalExclusions.clear();
        for (NbtElement element : tag.getList("BodyGlobalExclusions", NbtElement.INT_ARRAY_TYPE)) {
            UUID uuid = NbtHelper.toUuid(element);
            if (uuid != null) {
                this.bodyGlobalExclusions.add(uuid);
            }
        }
        this.identityEntries.clear();
        for (NbtElement element : tag.getList("Identities", NbtElement.COMPOUND_TYPE)) {
            NbtCompound identityTag = (NbtCompound) element;
            if (identityTag.contains("PlayerUuid")) {
                DisguiseEntry entry = DisguiseEntry.fromNbt(identityTag);
                if (entry != null) {
                    this.identityEntries.put(identityTag.getUuid("PlayerUuid"), entry);
                }
            }
        }
        this.identityGlobalExclusions.clear();
        for (NbtElement element : tag.getList("IdentityGlobalExclusions", NbtElement.INT_ARRAY_TYPE)) {
            UUID uuid = NbtHelper.toUuid(element);
            if (uuid != null) {
                this.identityGlobalExclusions.add(uuid);
            }
        }
        this.chatIdentityEntries.clear();
        for (NbtElement element : tag.getList("ChatIdentities", NbtElement.COMPOUND_TYPE)) {
            NbtCompound chatTag = (NbtCompound) element;
            if (chatTag.contains("PlayerUuid")) {
                DisguiseEntry entry = DisguiseEntry.fromNbt(chatTag);
                if (entry != null) this.chatIdentityEntries.put(chatTag.getUuid("PlayerUuid"), entry);
            }
        }
        this.chatIdentityGlobalExclusions.clear();
        for (NbtElement element : tag.getList("ChatIdentityGlobalExclusions", NbtElement.INT_ARRAY_TYPE)) {
            UUID uuid = NbtHelper.toUuid(element);
            if (uuid != null) this.chatIdentityGlobalExclusions.add(uuid);
        }
        this.replayIdentityEntries.clear();
        for (NbtElement element : tag.getList("ReplayIdentities", NbtElement.COMPOUND_TYPE)) {
            NbtCompound replayTag = (NbtCompound) element;
            if (replayTag.contains("PlayerUuid")) {
                DisguiseEntry entry = DisguiseEntry.fromNbt(replayTag);
                if (entry != null) {
                    this.replayIdentityEntries.put(replayTag.getUuid("PlayerUuid"), entry);
                }
            }
        }
        this.replayIdentityGlobalExclusions.clear();
        for (NbtElement element : tag.getList("ReplayIdentityGlobalExclusions", NbtElement.INT_ARRAY_TYPE)) {
            UUID uuid = NbtHelper.toUuid(element);
            if (uuid != null) {
                this.replayIdentityGlobalExclusions.add(uuid);
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        if (this.globalEntry != null) {
            tag.put("Global", this.globalEntry.toNbt());
        }
        if (this.bodyGlobalEntry != null) {
            tag.put("BodyGlobal", this.bodyGlobalEntry.toNbt());
        }
        if (this.identityGlobalEntry != null) {
            tag.put("IdentityGlobal", this.identityGlobalEntry.toNbt());
        }
        if (this.chatIdentityGlobalEntry != null) tag.put("ChatIdentityGlobal", this.chatIdentityGlobalEntry.toNbt());
        if (this.replayIdentityGlobalEntry != null) {
            tag.put("ReplayIdentityGlobal", this.replayIdentityGlobalEntry.toNbt());
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
        NbtList bodies = new NbtList();
        for (Map.Entry<UUID, DisguiseEntry> entry : this.bodyEntries.entrySet()) {
            NbtCompound bodyTag = entry.getValue().toNbt();
            bodyTag.putUuid("OwnerUuid", entry.getKey());
            bodies.add(bodyTag);
        }
        tag.put("Bodies", bodies);
        NbtList bodyExclusions = new NbtList();
        for (UUID uuid : this.bodyGlobalExclusions) {
            bodyExclusions.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("BodyGlobalExclusions", bodyExclusions);
        NbtList identities = new NbtList();
        for (Map.Entry<UUID, DisguiseEntry> entry : this.identityEntries.entrySet()) {
            NbtCompound identityTag = entry.getValue().toNbt();
            identityTag.putUuid("PlayerUuid", entry.getKey());
            identities.add(identityTag);
        }
        tag.put("Identities", identities);
        NbtList identityExclusions = new NbtList();
        for (UUID uuid : this.identityGlobalExclusions) {
            identityExclusions.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("IdentityGlobalExclusions", identityExclusions);
        NbtList chatIdentities = new NbtList();
        for (Map.Entry<UUID, DisguiseEntry> entry : this.chatIdentityEntries.entrySet()) {
            NbtCompound chatTag = entry.getValue().toNbt();
            chatTag.putUuid("PlayerUuid", entry.getKey());
            chatIdentities.add(chatTag);
        }
        tag.put("ChatIdentities", chatIdentities);
        NbtList chatExclusions = new NbtList();
        for (UUID uuid : this.chatIdentityGlobalExclusions) chatExclusions.add(NbtHelper.fromUuid(uuid));
        tag.put("ChatIdentityGlobalExclusions", chatExclusions);
        NbtList replayIdentities = new NbtList();
        for (Map.Entry<UUID, DisguiseEntry> entry : this.replayIdentityEntries.entrySet()) {
            NbtCompound replayTag = entry.getValue().toNbt();
            replayTag.putUuid("PlayerUuid", entry.getKey());
            replayIdentities.add(replayTag);
        }
        tag.put("ReplayIdentities", replayIdentities);
        NbtList replayExclusions = new NbtList();
        for (UUID uuid : this.replayIdentityGlobalExclusions) {
            replayExclusions.add(NbtHelper.fromUuid(uuid));
        }
        tag.put("ReplayIdentityGlobalExclusions", replayExclusions);
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
