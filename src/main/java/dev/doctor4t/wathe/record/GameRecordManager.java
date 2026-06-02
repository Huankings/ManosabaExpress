package dev.doctor4t.wathe.record;

import com.mojang.authlib.GameProfile;
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.api.event.RecordEvents;
import dev.doctor4t.wathe.cca.GameRoundEndComponent;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.record.replay.ReplayGenerator;
import dev.doctor4t.wathe.util.ShopEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 对局事件记录管理器。
 *
 * <p>这里专注做“采集与保存”，不负责决定最终给玩家看到什么文本；
 * 展示层统一由 replay 包格式化。这样扩展职业模组既能直接复用本体事件，
 * 也能按需注册自己的格式化器和额外字段。</p>
 */
public final class GameRecordManager {
    private GameRecordManager() {
    }

    public static final class MatchRecord {
        private final UUID matchId;
        private final Identifier dimensionId;
        private final Identifier gameModeId;
        private final Identifier mapEffectId;
        private final long startTick;
        private final long startMs;
        private final List<GameRecordEvent> events = new ArrayList<>();
        private final Set<UUID> roleSnapshotRecorded = new HashSet<>();
        private boolean active = true;
        private boolean initialSnapshotComplete = false;
        private int nextSeq = 0;

        private MatchRecord(UUID matchId, Identifier dimensionId, Identifier gameModeId, Identifier mapEffectId, long startTick, long startMs) {
            this.matchId = matchId;
            this.dimensionId = dimensionId;
            this.gameModeId = gameModeId;
            this.mapEffectId = mapEffectId;
            this.startTick = startTick;
            this.startMs = startMs;
        }

        public UUID getMatchId() {
            return matchId;
        }

        public Identifier getDimensionId() {
            return dimensionId;
        }

        public Identifier getGameModeId() {
            return gameModeId;
        }

        public Identifier getMapEffectId() {
            return mapEffectId;
        }

        public long getStartTick() {
            return startTick;
        }

        public long getStartMs() {
            return startMs;
        }

        public List<GameRecordEvent> getEvents() {
            return Collections.unmodifiableList(events);
        }

        private void addEvent(String type, long worldTick, long realTimeMs, NbtCompound data) {
            events.add(new GameRecordEvent(matchId, nextSeq++, type, worldTick, realTimeMs, data));
        }
    }

    private static MatchRecord currentMatch = null;
    private static MatchRecord lastFinishedMatch = null;
    private static final Set<UUID> connectedPlayers = new HashSet<>();

    public static synchronized boolean hasActiveMatch() {
        return currentMatch != null && currentMatch.active;
    }

    public static synchronized @Nullable MatchRecord getCurrentMatch() {
        return currentMatch;
    }

    public static synchronized @Nullable MatchRecord getLastFinishedMatch() {
        return lastFinishedMatch;
    }

    public static synchronized void startMatch(ServerWorld world, GameWorldComponent gameComponent) {
        if (currentMatch != null && currentMatch.active) {
            endMatch(world);
        }

        Identifier dimensionId = world.getRegistryKey().getValue();
        Identifier gameModeId = gameComponent.getGameMode() != null ? gameComponent.getGameMode().identifier : Identifier.of("wathe", "unknown");
        Identifier mapEffectId = gameComponent.getMapEffect() != null ? gameComponent.getMapEffect().identifier : Identifier.of("wathe", "unknown");
        currentMatch = new MatchRecord(UUID.randomUUID(), dimensionId, gameModeId, mapEffectId, world.getTime(), System.currentTimeMillis());
        connectedPlayers.clear();
    }

    public static synchronized void recordMatchStart(ServerWorld world, GameWorldComponent gameComponent) {
        if (!hasActiveMatch()) {
            return;
        }

        MatchRecord match = currentMatch;
        NbtCompound data = new NbtCompound();
        data.putString("game_mode", match.gameModeId.toString());
        data.putString("map_effect", match.mapEffectId.toString());
        data.putInt("player_count", gameComponent.getRoles().size());
        addEvent(world, GameRecordTypes.MATCH_START, null, null, data);

        for (ServerPlayerEntity player : world.getPlayers()) {
            if (GameFunctions.isPlayerAliveAndSurvival(player)) {
                recordPlayerJoinInternal(player);
            }
        }
    }

    public static synchronized void recordRoleSnapshot(ServerWorld world, GameWorldComponent gameComponent) {
        if (!hasActiveMatch()) {
            return;
        }

        MatchRecord match = currentMatch;
        for (Map.Entry<UUID, Role> entry : gameComponent.getRoles().entrySet()) {
            UUID uuid = entry.getKey();
            Role role = entry.getValue();
            if (role == null) {
                continue;
            }
            if (!match.roleSnapshotRecorded.add(uuid)) {
                continue;
            }
            GameProfile profile = world.getServer().getUserCache().getByUuid(uuid).orElse(null);
            NbtCompound data = new NbtCompound();
            data.put("player", buildPlayerSnapshot(uuid, profile, role));
            addEvent(world, GameRecordTypes.ROLE_ASSIGNED, null, null, data);
        }
    }

    public static synchronized void endMatch(ServerWorld world) {
        if (!hasActiveMatch()) {
            return;
        }

        MatchRecord match = currentMatch;
        GameRoundEndComponent roundEnd = GameRoundEndComponent.KEY.get(world);
        GameFunctions.WinStatus winStatus = roundEnd.getWinStatus();

        NbtCompound endData = new NbtCompound();
        endData.putString("win_status", winStatus.name());
        addEvent(world, GameRecordTypes.MATCH_END, null, null, endData);

        for (GameRoundEndComponent.RoundEndData entry : roundEnd.getPlayers()) {
            NbtCompound data = new NbtCompound();
            data.putUuid("player", entry.player().getId());
            data.putBoolean("was_dead", entry.wasDead());
            data.putBoolean("is_winner", roundEnd.didWin(entry.player().getId()));
            data.putString("team_role_translation", roundEnd.getRoleDisplay(entry.player().getId()).translationKey());
            data.putString("team_role_fallback", roundEnd.getRoleDisplay(entry.player().getId()).fallbackName());
            data.putInt("team_role_color", roundEnd.getRoleDisplay(entry.player().getId()).color());
            addEvent(world, GameRecordTypes.PLAYER_RESULT, null, null, data);
        }

        match.active = false;
        lastFinishedMatch = match;
        currentMatch = null;
        connectedPlayers.clear();
        RecordEvents.ON_RECORD_END.invoker().onRecordEnd(world, match);
    }

    /**
     * 在整套开局逻辑与所有 {@code ON_FINISH_INITIALIZE} 监听器都执行完之后调用。
     *
     * <p>这里才真正“锁定初始职业快照”并开启后续的转职记录，
     * 可以避免 Harpy / NoellesRoles / StupidExpress 在开局阶段的补充赋职
     * 被误记成局中转职。</p>
     */
    public static synchronized void completeInitialization(ServerWorld world, GameWorldComponent gameComponent) {
        if (!hasActiveMatch()) {
            return;
        }

        MatchRecord match = currentMatch;
        if (match == null || match.initialSnapshotComplete) {
            return;
        }

        recordMatchStart(world, gameComponent);
        recordRoleSnapshot(world, gameComponent);
        match.initialSnapshotComplete = true;
    }

    public static synchronized boolean isInitialSnapshotComplete() {
        return currentMatch != null && currentMatch.initialSnapshotComplete;
    }

    public static void recordPlayerJoin(ServerPlayerEntity player) {
        if (!hasActiveMatch()) {
            return;
        }
        recordPlayerJoinInternal(player);
    }

    public static void recordPlayerLeave(ServerPlayerEntity player) {
        if (!hasActiveMatch()) {
            return;
        }
        if (!connectedPlayers.remove(player.getUuid())) {
            return;
        }
        NbtCompound data = new NbtCompound();
        putPos(data, "pos", player.getPos());
        addEvent(player.getServerWorld(), GameRecordTypes.PLAYER_LEAVE, player, null, data);
    }

    public static void recordShopPurchase(ServerPlayerEntity player, ShopEntry entry, int index, int pricePaid) {
        recordShopPurchase(player, entry.stack(), index, pricePaid, entry.price());
    }

    /**
     * 记录一次商店购买。
     *
     * <p>给扩展职业模组使用时，推荐直接把“本次真实购买到的 ItemStack”传进来，
     * 不要再依赖原版商店索引反查。这样即使某个职业把第 1 格换成完全不同的自定义商品，
     * 回放仍然能显示真实购买结果。</p>
     */
    public static void recordShopPurchase(ServerPlayerEntity player, ItemStack purchasedStack, int index, int pricePaid) {
        recordShopPurchase(player, purchasedStack, index, pricePaid, pricePaid);
    }

    private static void recordShopPurchase(ServerPlayerEntity player, ItemStack purchasedStack, int index, int pricePaid, int listedPrice) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = new NbtCompound();
        data.putInt("index", index);
        data.putInt("price", listedPrice);
        data.putInt("price_paid", pricePaid);
        data.putString("item", Registries.ITEM.getId(purchasedStack.getItem()).toString());
        data.putString("item_name", Text.Serialization.toJsonString(purchasedStack.getName(), player.getRegistryManager()));
        data.putInt("count", purchasedStack.getCount());
        data.putInt("balance_after", PlayerShopComponent.KEY.get(player).balance);
        addEvent(player.getServerWorld(), GameRecordTypes.SHOP_PURCHASE, player, null, data);
    }

    public static void recordTaskComplete(ServerPlayerEntity player, String taskName) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = new NbtCompound();
        data.putString("task", taskName);
        addEvent(player.getServerWorld(), GameRecordTypes.TASK_COMPLETE, player, null, data);
    }

    public static void recordRoleChange(ServerWorld world, UUID playerUuid, @Nullable Role oldRole, @Nullable Role newRole) {
        if (!hasActiveMatch() || !isInitialSnapshotComplete()) {
            return;
        }
        NbtCompound data = new NbtCompound();
        data.putUuid("player", playerUuid);
        if (oldRole != null) {
            data.putString("old_role", oldRole.identifier().toString());
            data.putString("old_faction", oldRole.getFaction().name());
        }
        if (newRole != null) {
            data.putString("new_role", newRole.identifier().toString());
            data.putString("new_faction", newRole.getFaction().name());
        }
        addEvent(world, GameRecordTypes.ROLE_CHANGED, null, null, data);
    }

    public static void recordPoisoned(ServerPlayerEntity victim, @Nullable UUID poisonerId, int ticks, Identifier source, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        ServerPlayerEntity poisoner = poisonerId == null ? null : victim.getServer().getPlayerManager().getPlayer(poisonerId);
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putInt("ticks", ticks);
        data.putString("source", source.toString());
        if (poisonerId != null) {
            data.putUuid("poisoner_uuid", poisonerId);
        }
        addEvent(victim.getServerWorld(), GameRecordTypes.PLAYER_POISONED, poisoner, victim, data);
    }

    public static void recordDeath(ServerPlayerEntity victim, @Nullable ServerPlayerEntity killer, Identifier deathReason, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("death_reason", deathReason.toString());
        addEvent(victim.getServerWorld(), GameRecordTypes.DEATH, killer, victim, data);
    }

    public static void recordDeath(ServerPlayerEntity victim, @Nullable ServerPlayerEntity killer, Identifier deathReason) {
        recordDeath(victim, killer, deathReason, null);
    }

    public static void recordItemPickup(ServerPlayerEntity player, ItemStack stack, int count) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = new NbtCompound();
        data.putString("item", Registries.ITEM.getId(stack.getItem()).toString());
        data.putString("item_name", Text.Serialization.toJsonString(stack.getName(), player.getRegistryManager()));
        data.putInt("count", count);
        addEvent(player.getServerWorld(), GameRecordTypes.ITEM_PICKUP, player, null, data);
    }

    public static void recordItemUse(ServerPlayerEntity player, Identifier itemId, @Nullable ServerPlayerEntity target, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("item", itemId.toString());
        addEvent(player.getServerWorld(), GameRecordTypes.ITEM_USE, player, target, data);
    }

    public static void recordItemHit(ServerPlayerEntity player, Identifier itemId, @Nullable ServerPlayerEntity target, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("item", itemId.toString());
        addEvent(player.getServerWorld(), GameRecordTypes.ITEM_HIT, player, target, data);
    }

    /**
     * 使用真实 ItemStack 记录一次命中事件。
     *
     * <p>相比只传物品 ID，这个重载会额外把当前显示名一并写进回放数据，
     * 适合扩展模组的自定义武器直接接入。这样即使后续某些武器做了别名、
     * 覆写显示名，回放也能尽量还原玩家当时手里的真实物品。</p>
     */
    public static void recordItemHit(ServerPlayerEntity player, ItemStack stack, @Nullable ServerPlayerEntity target, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        GameFunctions.putReplayItemData(data, player.getServerWorld(), stack);
        addEvent(player.getServerWorld(), GameRecordTypes.ITEM_HIT, player, target, data);
    }

    /**
     * 记录一次“带命中类别”的命中事件。
     *
     * <p>hitType 用来告诉回放层：这次虽然可能是扩展模组自定义物品，
     * 但语义上属于“刀命中 / 枪命中 / 球棒命中”中的哪一种，
     * 从而复用本体已有的句式模板。</p>
     */
    public static void recordItemHit(ServerPlayerEntity player, ItemStack stack, Identifier hitType, @Nullable ServerPlayerEntity target, @Nullable NbtCompound extra) {
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("hit_type", hitType.toString());
        recordItemHit(player, stack, target, data);
    }

    public static void recordPlatterTake(ServerPlayerEntity player, Identifier itemId, BlockPos platterPos, @Nullable String poisoner, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("item", itemId.toString());
        putBlockPos(data, "pos", platterPos);
        if (poisoner != null) {
            data.putUuid("poisoner", UUID.fromString(poisoner));
        }
        addEvent(player.getServerWorld(), GameRecordTypes.PLATTER_TAKE, player, null, data);
    }

    public static void recordConsumeItem(ServerPlayerEntity player, ItemStack stack, String consumeType, boolean poisoned, @Nullable UUID poisoner, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("item", Registries.ITEM.getId(stack.getItem()).toString());
        data.putString("item_name", Text.Serialization.toJsonString(stack.getName(), player.getRegistryManager()));
        data.putString("consume_type", consumeType);
        data.putBoolean("poisoned", poisoned);
        if (poisoner != null) {
            data.putUuid("poisoner", poisoner);
        }
        addEvent(player.getServerWorld(), GameRecordTypes.CONSUME_ITEM, player, null, data);
    }

    public static void recordSkillUse(ServerPlayerEntity player, Identifier skillId, @Nullable ServerPlayerEntity target, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("skill", skillId.toString());
        addEvent(player.getServerWorld(), GameRecordTypes.SKILL_USE, player, target, data);
    }

    public static void recordGlobalEvent(ServerWorld world, Identifier eventId, @Nullable ServerPlayerEntity source, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("event", eventId.toString());
        addEvent(world, GameRecordTypes.GLOBAL_EVENT, source, null, data);
    }

    public static void recordDoorInteraction(ServerPlayerEntity player, BlockPos doorPos, String interactionType, String doorType, boolean success) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = new NbtCompound();
        data.putString("interaction_type", interactionType);
        data.putString("door_type", doorType);
        data.putBoolean("success", success);
        putBlockPos(data, "pos", doorPos);
        addEvent(player.getServerWorld(), GameRecordTypes.DOOR_INTERACTION, player, null, data);
    }

    public static void recordShieldBlocked(ServerPlayerEntity victim, @Nullable ServerPlayerEntity attacker, Identifier source, @Nullable Identifier damageItem, @Nullable NbtCompound extra) {
        if (!hasActiveMatch()) {
            return;
        }
        NbtCompound data = extra == null ? new NbtCompound() : extra.copy();
        data.putString("source", source.toString());
        if (damageItem != null) {
            data.putString("item", damageItem.toString());
        }
        addEvent(victim.getServerWorld(), GameRecordTypes.SHIELD_BLOCKED, attacker, victim, data);
    }

    /**
     * 便于扩展模组直接塞自定义事件。
     *
     * <p>例如 NoellesRoles 后续可以直接调用：</p>
     * <pre>{@code
     * GameRecordManager.event(GameRecordTypes.GLOBAL_EVENT)
     *     .world(world)
     *     .actor(player)
     *     .put("event", "noellesroles:swapper_swap")
     *     .record();
     * }</pre>
     */
    public static EventBuilder event(String type) {
        return new EventBuilder(type);
    }

    public static final class EventBuilder {
        private final String type;
        private ServerWorld world;
        private ServerPlayerEntity actor;
        private ServerPlayerEntity target;
        private final NbtCompound data = new NbtCompound();

        private EventBuilder(String type) {
            this.type = type;
        }

        public EventBuilder world(ServerWorld world) {
            this.world = world;
            return this;
        }

        public EventBuilder actor(ServerPlayerEntity actor) {
            this.actor = actor;
            if (this.world == null && actor != null) {
                this.world = actor.getServerWorld();
            }
            return this;
        }

        public EventBuilder target(ServerPlayerEntity target) {
            this.target = target;
            return this;
        }

        public EventBuilder put(String key, String value) {
            this.data.putString(key, value);
            return this;
        }

        public EventBuilder putInt(String key, int value) {
            this.data.putInt(key, value);
            return this;
        }

        public EventBuilder putBool(String key, boolean value) {
            this.data.putBoolean(key, value);
            return this;
        }

        public EventBuilder putUuid(String key, UUID value) {
            this.data.putUuid(key, value);
            return this;
        }

        public EventBuilder putBlockPos(String key, BlockPos pos) {
            GameRecordManager.putBlockPos(this.data, key, pos);
            return this;
        }

        public EventBuilder putPos(String key, Vec3d pos) {
            GameRecordManager.putPos(this.data, key, pos);
            return this;
        }

        public EventBuilder putNbt(String key, NbtCompound nbt) {
            this.data.put(key, nbt.copy());
            return this;
        }

        public void record() {
            if (!hasActiveMatch() || this.world == null) {
                return;
            }
            addEvent(this.world, this.type, this.actor, this.target, this.data);
        }
    }

    public static void putPos(NbtCompound data, String key, Vec3d pos) {
        NbtCompound posTag = new NbtCompound();
        posTag.putDouble("x", pos.x);
        posTag.putDouble("y", pos.y);
        posTag.putDouble("z", pos.z);
        data.put(key, posTag);
    }

    public static void putBlockPos(NbtCompound data, String key, BlockPos pos) {
        NbtCompound posTag = new NbtCompound();
        posTag.putInt("x", pos.getX());
        posTag.putInt("y", pos.getY());
        posTag.putInt("z", pos.getZ());
        data.put(key, posTag);
    }

    private static void recordPlayerJoinInternal(ServerPlayerEntity player) {
        if (!connectedPlayers.add(player.getUuid())) {
            return;
        }
        NbtCompound data = new NbtCompound();
        putPos(data, "pos", player.getPos());
        addEvent(player.getServerWorld(), GameRecordTypes.PLAYER_JOIN, player, null, data);
    }

    private static void addEvent(ServerWorld world, String type, @Nullable ServerPlayerEntity actor, @Nullable ServerPlayerEntity target, @Nullable NbtCompound data) {
        if (!hasActiveMatch()) {
            return;
        }
        MatchRecord match = currentMatch;
        NbtCompound payload = data == null ? new NbtCompound() : data.copy();
        if (actor != null) {
            payload.putUuid("actor", actor.getUuid());
        }
        if (target != null) {
            payload.putUuid("target", target.getUuid());
        }
        match.addEvent(type, world.getTime(), System.currentTimeMillis(), payload);

        /*
         * 记录完成后立刻尝试发送局内实时播报。
         *
         * 注意：
         * 这里不会再无差别广播给所有人，而是由 ReplayGenerator 按玩家当前状态过滤：
         * 1. 对局进行中，只有非存活玩家（旁观 / 创造 / 已死亡）能实时看到；
         * 2. 对局结束后，再给所有玩家完整重播整局记录。
         *
         * 这样可以避开 talkbubbles 一类“把聊天消息显示成头顶气泡”的模组泄露问题。
         */
        GameRecordEvent latestEvent = match.events.get(match.events.size() - 1);
        ReplayGenerator.sendLiveEvent(world, match, latestEvent);
    }

    /**
     * 构建开局职业快照。
     *
     * <p>这里会把职业颜色、阵营、职业翻译键都一起存下，
     * 这样即便后续扩展职业中途转职，回放也仍然能稳定显示旧职业与新职业。</p>
     */
    private static NbtCompound buildPlayerSnapshot(UUID uuid, @Nullable GameProfile profile, @Nullable Role role) {
        NbtCompound info = new NbtCompound();
        info.putUuid("uuid", uuid);
        info.putString("name", profile != null ? profile.getName() : uuid.toString());
        if (role != null) {
            info.putString("role", role.identifier().toString());
            info.putInt("role_color", role.color());
            Faction faction = role.getFaction();
            info.putString("faction", faction.name());
            info.putInt("faction_color", faction.displayColor());
        }
        return info;
    }
}
