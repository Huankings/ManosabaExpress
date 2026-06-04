package dev.doctor4t.wathe.record.replay;

import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.record.GameRecordEvent;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.record.GameRecordTypes;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

/**
 * 负责把一局记录变成聊天栏可读回放。
 */
public final class ReplayGenerator {
    private static Map<UUID, PlayerInfo> contextualPlayerInfoCache;
    private static @Nullable PlayerDisplayMode contextualPlayerDisplayMode;

    private ReplayGenerator() {
    }

    /**
     * 回放期间使用的玩家显示缓存。
     *
     * <p>nameColor 是玩家名字的阵营色；
     * roleColor 是括号里职业名自己的颜色。</p>
     */
    public record PlayerInfo(String name, String roleTranslationKey, String roleFallback, int roleColor, Faction faction, int nameColor) {
    }

    /**
     * 玩家名在不同回放场景下的显示模式。
     *
     * <p>Wathe 默认完整回放仍然沿用“名字(职业)”：
     * 这样局后复盘时信息最完整。
     *
     * <p>但扩展模组有时也需要“只展示玩家名字、不泄露职业”的文本，
     * 例如追忆者把局内事件写进成书时，就应该隐藏职业信息。
     * 因此这里把显示模式抽成一个轻量枚举，供外部按需切换。</p>
     */
    public enum PlayerDisplayMode {
        /**
         * 默认模式：玩家名后面带职业名。
         */
        NAME_WITH_ROLE,
        /**
         * 仅显示玩家名字，不附带职业。
         */
        NAME_ONLY
    }

    public static void generateAndSend(ServerWorld world, GameRecordManager.MatchRecord match) {
        Map<UUID, PlayerInfo> playerInfoCache = buildInitialPlayerInfoCache(match);
        List<Text> replayLines = generateReplayLinesInternal(
                match,
                world,
                playerInfoCache,
                event -> true,
                PlayerDisplayMode.NAME_WITH_ROLE
        );

        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            sendReplayToPlayer(player, replayLines);
        }
    }

    public static void sendLiveEvent(ServerWorld world, GameRecordManager.MatchRecord match, GameRecordEvent event) {
        Map<UUID, PlayerInfo> playerInfoCache = buildPlayerInfoCacheUntil(match, event);
        setContextualPlayerInfoCache(playerInfoCache);
        setContextualPlayerDisplayMode(PlayerDisplayMode.NAME_WITH_ROLE);
        DefaultReplayFormatters.setPlayerInfoCache(playerInfoCache);
        ReplayEventFormatter formatter = ReplayRegistry.getFormatter(event.type());
        if (formatter == null) {
            DefaultReplayFormatters.clearPlayerInfoCache();
            clearContextualPlayerInfoCache();
            clearContextualPlayerDisplayMode();
            return;
        }
        Text formatted = formatter.format(event, match, world);
        DefaultReplayFormatters.clearPlayerInfoCache();
        clearContextualPlayerInfoCache();
        clearContextualPlayerDisplayMode();
        if (formatted == null) {
            return;
        }
        String timeStr = formatTime(event.worldTick(), match.getStartTick());
        MutableText message = Text.literal("[" + timeStr + "] ").formatted(Formatting.GRAY).append(formatted);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (shouldReceiveLiveReplay(player, world)) {
                player.sendMessage(message, false);
            }
        }
    }

    /**
     * 局内实时播报只发给“非存活玩家”。
     *
     * <p>原因是用户安装了 talkbubbles 之后，任何收到的聊天文本都可能被渲染成头顶气泡。
     * 如果继续把回放事件广播给局内活人，就会把隐蔽事件直接暴露给他们。</p>
     *
     * <p>因此这里约定：
     * 1. 对局运行中：只有旁观 / 创造 / 已死亡玩家可见；
     * 2. 对局结束后：完整回放仍由 {@link #generateAndSend(ServerWorld, GameRecordManager.MatchRecord)}
     * 发给所有玩家。</p>
     */
    private static boolean shouldReceiveLiveReplay(ServerPlayerEntity player, ServerWorld world) {
        if (GameWorldComponent.KEY.get(world).isRunning()) {
            return !GameFunctions.isPlayerAliveAndSurvival(player);
        }
        return true;
    }

    /**
     * 只构建“开局初始职业快照”缓存。
     *
     * <p>这份缓存不会把 ROLE_CHANGED 套进去，
     * 供完整回放生成时按时间线逐条推进使用。</p>
     */
    private static Map<UUID, PlayerInfo> buildInitialPlayerInfoCache(GameRecordManager.MatchRecord match) {
        Map<UUID, PlayerInfo> cache = new HashMap<>();

        for (GameRecordEvent event : match.getEvents()) {
            if (GameRecordTypes.ROLE_ASSIGNED.equals(event.type())) {
                putAssignedSnapshot(cache, event.data().getCompound("player"));
            }
        }

        return cache;
    }

    /**
     * 构建“截至某条事件时刻”为止的玩家职业显示缓存。
     *
     * <p>实时播报需要看到“当前这一刻”的职业状态，
     * 因此会从开局快照开始，顺序吃到当前事件为止的所有 ROLE_CHANGED。
     * 这样像秃鹫 / 仇杀客这种中途转职角色：
     * 转职前事件仍显示旧职业，转职后事件才显示新职业。</p>
     */
    private static Map<UUID, PlayerInfo> buildPlayerInfoCacheUntil(GameRecordManager.MatchRecord match, GameRecordEvent limitEvent) {
        Map<UUID, PlayerInfo> cache = buildInitialPlayerInfoCache(match);
        List<GameRecordEvent> sortedEvents = sortedEvents(match);
        for (GameRecordEvent event : sortedEvents) {
            if (event.seq() > limitEvent.seq()) {
                break;
            }
            applyRoleChange(cache, event);
        }
        return cache;
    }

    private static void putAssignedSnapshot(Map<UUID, PlayerInfo> cache, NbtCompound playerData) {
        UUID uuid = playerData.getUuid("uuid");
        String name = playerData.getString("name");
        Identifier roleId = Identifier.tryParse(playerData.getString("role"));
        Role role = WatheRoles.getRole(roleId);
        if (role != null) {
            cache.put(uuid, buildPlayerInfo(name, role));
            return;
        }

        int roleColor = playerData.contains("role_color") ? playerData.getInt("role_color") : 0xFFFFFF;
        Faction faction = playerData.contains("faction") ? safeFaction(playerData.getString("faction")) : Faction.CIVILIAN;
        int nameColor = playerData.contains("faction_color") ? playerData.getInt("faction_color") : faction.displayColor();
        String roleTranslationKey = buildRoleTranslationKey(roleId);
        String fallback = roleId == null ? "Unknown" : prettifyIdentifierPath(roleId.getPath());
        cache.put(uuid, new PlayerInfo(name, roleTranslationKey, fallback, roleColor, faction, nameColor));
    }

    private static @NotNull PlayerInfo buildPlayerInfo(@NotNull String playerName, @NotNull Role role) {
        return new PlayerInfo(
                playerName,
                buildRoleTranslationKey(role.identifier()),
                prettifyIdentifierPath(role.identifier().getPath()),
                role.color(),
                role.getFaction(),
                role.getFaction().displayColor()
        );
    }

    private static String buildRoleTranslationKey(@Nullable Identifier roleId) {
        if (roleId == null) {
            return "replay.role.unknown";
        }
        if ("wathe".equals(roleId.getNamespace())) {
            return "announcement.title." + roleId.getPath();
        }
        return "announcement.role." + roleId.getNamespace() + "." + roleId.getPath();
    }

    public static Map<UUID, PlayerInfo> getPlayerInfoCache(GameRecordManager.MatchRecord match) {
        if (contextualPlayerInfoCache != null) {
            return contextualPlayerInfoCache;
        }
        return buildInitialPlayerInfoCache(match);
    }

    /**
     * 生成一组“可直接展示”的回放文本，并允许调用方：
     * 1. 只筛选自己关心的事件；
     * 2. 指定玩家名显示模式。
     *
     * <p>这个 helper 的意义主要在于给扩展模组复用：
     * 它们不需要自己重新实现一套事件格式化逻辑，
     * 只要把事件过滤条件传进来，就能继续吃到 Wathe 与所有扩展模组已注册的 formatter。</p>
     */
    public static List<Text> generateReplayLines(
            GameRecordManager.MatchRecord match,
            ServerWorld world,
            @Nullable Predicate<GameRecordEvent> eventFilter,
            PlayerDisplayMode displayMode
    ) {
        Map<UUID, PlayerInfo> playerInfoCache = buildInitialPlayerInfoCache(match);
        return generateReplayLinesInternal(
                match,
                world,
                playerInfoCache,
                eventFilter == null ? event -> true : eventFilter,
                displayMode
        );
    }

    private static List<Text> generateReplayLinesInternal(
            GameRecordManager.MatchRecord match,
            ServerWorld world,
            Map<UUID, PlayerInfo> playerInfoCache,
            Predicate<GameRecordEvent> eventFilter,
            PlayerDisplayMode displayMode
    ) {
        List<Text> lines = new ArrayList<>();
        long startTick = match.getStartTick();
        List<GameRecordEvent> sortedEvents = sortedEvents(match);

        for (GameRecordEvent event : sortedEvents) {
            Map<UUID, PlayerInfo> eventCache = new HashMap<>(playerInfoCache);
            setContextualPlayerInfoCache(eventCache);
            setContextualPlayerDisplayMode(displayMode);
            DefaultReplayFormatters.setPlayerInfoCache(eventCache);
            ReplayEventFormatter formatter = ReplayRegistry.getFormatter(event.type());
            if (formatter == null) {
                DefaultReplayFormatters.clearPlayerInfoCache();
                clearContextualPlayerInfoCache();
                clearContextualPlayerDisplayMode();
                applyRoleChange(playerInfoCache, event);
                continue;
            }
            Text formatted = formatter.format(event, match, world);
            DefaultReplayFormatters.clearPlayerInfoCache();
            clearContextualPlayerInfoCache();
            clearContextualPlayerDisplayMode();
            applyRoleChange(playerInfoCache, event);
            if (formatted == null || !eventFilter.test(event)) {
                continue;
            }
            String timeStr = formatTime(event.worldTick(), startTick);
            lines.add(Text.literal("[" + timeStr + "] ").formatted(Formatting.GRAY).append(formatted));
        }

        return lines;
    }

    private static void setContextualPlayerInfoCache(Map<UUID, PlayerInfo> cache) {
        contextualPlayerInfoCache = cache;
    }

    private static void clearContextualPlayerInfoCache() {
        contextualPlayerInfoCache = null;
    }

    private static void setContextualPlayerDisplayMode(PlayerDisplayMode mode) {
        contextualPlayerDisplayMode = mode;
    }

    private static void clearContextualPlayerDisplayMode() {
        contextualPlayerDisplayMode = null;
    }

    private static List<GameRecordEvent> sortedEvents(GameRecordManager.MatchRecord match) {
        List<GameRecordEvent> sortedEvents = new ArrayList<>(match.getEvents());
        sortedEvents.sort(Comparator.comparingLong(GameRecordEvent::worldTick).thenComparingInt(GameRecordEvent::seq));
        return sortedEvents;
    }

    /**
     * 把一条 ROLE_CHANGED 事件推进到当前玩家显示缓存里。
     *
     * <p>注意这里只更新“后续事件应该看到的新职业”，
     * 当前这条 role_changed 事件自己的显示文本仍会使用推进前的缓存，
     * 因此玩家名会保持变更前职业，而句尾 old/new role 字段单独展示转变内容。</p>
     */
    private static void applyRoleChange(Map<UUID, PlayerInfo> cache, GameRecordEvent event) {
        if (!GameRecordTypes.ROLE_CHANGED.equals(event.type())) {
            return;
        }

        NbtCompound data = event.data();
        UUID uuid = data.containsUuid("player") ? data.getUuid("player") : null;
        if (uuid == null) {
            return;
        }

        PlayerInfo previous = cache.get(uuid);
        String playerName = previous != null ? previous.name() : uuid.toString();
        Role newRole = data.contains("new_role") ? WatheRoles.getRole(Identifier.tryParse(data.getString("new_role"))) : null;
        if (newRole != null) {
            cache.put(uuid, buildPlayerInfo(playerName, newRole));
        }
    }

    private static void sendReplayToPlayer(ServerPlayerEntity player, List<Text> replayLines) {
        player.sendMessage(Text.literal("═".repeat(40)).formatted(Formatting.DARK_GRAY), false);
        player.sendMessage(Text.translatable("replay.title").formatted(Formatting.GOLD, Formatting.BOLD), false);
        player.sendMessage(Text.empty(), false);

        for (Text line : replayLines) {
            player.sendMessage(line, false);
        }

        player.sendMessage(Text.empty(), false);
        player.sendMessage(Text.translatable("replay.footer").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("═".repeat(40)).formatted(Formatting.DARK_GRAY), false);
    }

    public static String formatTime(long tick, long startTick) {
        long elapsedTicks = tick - startTick;
        long totalSeconds = elapsedTicks / 20;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * 玩家显示格式：名字用阵营色，职业名用职业色。
     *
     * <p>例如：
     * A(交换者) 里的 A 按杀手/义警/平民/中立阵营着色，
     * 括号中的交换者则按职业自身颜色着色。</p>
     */
    public static Text formatPlayerName(UUID uuid, Map<UUID, PlayerInfo> playerInfoCache) {
        PlayerDisplayMode displayMode = contextualPlayerDisplayMode == null
                ? PlayerDisplayMode.NAME_WITH_ROLE
                : contextualPlayerDisplayMode;
        return formatPlayerName(uuid, playerInfoCache, displayMode);
    }

    /**
     * 按指定模式格式化玩家显示名。
     *
     * <p>默认公开入口仍会优先读取当前上下文模式，
     * 但如果调用方明确知道自己要什么显示方式，也可以直接走这个重载。</p>
     */
    public static Text formatPlayerName(UUID uuid, Map<UUID, PlayerInfo> playerInfoCache, PlayerDisplayMode displayMode) {
        PlayerInfo info = playerInfoCache.get(uuid);
        if (info == null) {
            return Text.literal(uuid.toString().substring(0, 8));
        }

        MutableText playerName = Text.literal(info.name()).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(info.nameColor())));
        if (displayMode == PlayerDisplayMode.NAME_ONLY) {
            return playerName;
        }

        MutableText roleText = Text.translatableWithFallback(info.roleTranslationKey(), info.roleFallback())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(info.roleColor())));
        return playerName.append(Text.literal("(").formatted(Formatting.WHITE))
                .append(roleText)
                .append(Text.literal(")").formatted(Formatting.WHITE));
    }

    public static Text formatItemName(NbtCompound data, ServerWorld world) {
        Text rawName = resolveItemName(data, world);
        return Text.literal("[")
                .append(rawName)
                .append(Text.literal("]"))
                .formatted(Formatting.WHITE);
    }

    public static Text resolveItemName(NbtCompound data, ServerWorld world) {
        if (data.contains("item_name")) {
            Text name = Text.Serialization.fromJson(data.getString("item_name"), world.getRegistryManager());
            if (name != null) {
                return name;
            }
        }

        String itemId = data.getString("item");
        if (itemId != null && !itemId.isEmpty()) {
            Identifier id = Identifier.tryParse(itemId);
            if (id != null) {
                Item item = Registries.ITEM.get(id);
                return Text.translatable(item.getTranslationKey());
            }
        }
        return Text.translatable("replay.item.unknown");
    }

    private static @NotNull Faction safeFaction(String name) {
        try {
            return Faction.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Faction.CIVILIAN;
        }
    }

    private static @NotNull String prettifyIdentifierPath(@NotNull String path) {
        String[] parts = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? path : builder.toString();
    }
}
