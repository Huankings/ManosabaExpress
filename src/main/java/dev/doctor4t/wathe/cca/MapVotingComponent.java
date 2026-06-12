package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.config.datapack.MapRegistry;
import dev.doctor4t.wathe.config.datapack.MapRegistryEntry;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 全局地图投票组件。
 *
 * <p>投票状态不能绑在某个世界上，因为玩家会被传送到不同维度；
 * 放在 ScoreboardComponent 上可以保证整台服务器只维护一份投票状态。</p>
 */
public class MapVotingComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<MapVotingComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("map_voting"), MapVotingComponent.class);

    public static final int VOTING_DURATION_TICKS = 30 * 20;
    public static final int ROULETTE_DURATION_TICKS = 8 * 20;
    public static final int ALL_VOTED_REMAINING_TICKS = 5 * 20;

    private final Scoreboard scoreboard;
    @Nullable
    private final MinecraftServer server;

    private boolean votingActive = false;
    private int votingTicksRemaining = 0;
    private final List<VotingMapEntry> availableMaps = new ArrayList<>();
    private final List<UnavailableMapEntry> unavailableMaps = new ArrayList<>();
    private int[] voteCounts = new int[0];
    private final Map<UUID, Integer> playerVotes = new HashMap<>();
    private int selectedMapIndex = -1;
    private boolean roulettePhase = false;
    private int rouletteTicksRemaining = 0;

    private boolean onlyOpVoting = false;
    private int randomMapCount = 0;

    @Nullable
    private Identifier lastSelectedDimension = null;

    public record VotingMapEntry(
            Identifier dimensionId,
            String displayName,
            String description,
            int minPlayers,
            int maxPlayers
    ) {
    }

    public record UnavailableMapEntry(
            Identifier dimensionId,
            String displayName,
            String reason
    ) {
    }

    public MapVotingComponent(Scoreboard scoreboard, @Nullable MinecraftServer server) {
        this.scoreboard = scoreboard;
        this.server = server;
    }

    public void sync() {
        KEY.sync(this.scoreboard);
    }

    public boolean isVotingActive() {
        return votingActive;
    }

    public int getVotingTicksRemaining() {
        return votingTicksRemaining;
    }

    public List<VotingMapEntry> getAvailableMaps() {
        return availableMaps;
    }

    public List<UnavailableMapEntry> getUnavailableMaps() {
        return unavailableMaps;
    }

    public int[] getVoteCounts() {
        return voteCounts;
    }

    public int getVotedMapIndex(UUID playerId) {
        return playerVotes.getOrDefault(playerId, -1);
    }

    public int getPlayerVoteCount() {
        return playerVotes.size();
    }

    public int getSelectedMapIndex() {
        return selectedMapIndex;
    }

    public boolean isRoulettePhase() {
        return roulettePhase;
    }

    public int getRouletteTicksRemaining() {
        return rouletteTicksRemaining;
    }

    public boolean isOnlyOpVoting() {
        return onlyOpVoting;
    }

    public int getRandomMapCount() {
        return randomMapCount;
    }

    @Nullable
    public Identifier getLastSelectedDimension() {
        return lastSelectedDimension;
    }

    public void setLastSelectedDimensionDirect(@Nullable Identifier dimensionId) {
        this.lastSelectedDimension = dimensionId;
        this.sync();
    }

    public void setOnlyOpVoting(boolean onlyOpVoting) {
        this.onlyOpVoting = onlyOpVoting;
        this.sync();
    }

    public void setRandomMapCount(int randomMapCount) {
        this.randomMapCount = randomMapCount;
        this.sync();
    }

    public void restartVoting() {
        reset();
        startVoting();
    }

    public void startVoting() {
        if (server == null) {
            Wathe.LOGGER.warn("Cannot start map voting: server reference is missing");
            return;
        }

        int playerCount = getOnlinePlayerCount();
        Map<Identifier, MapRegistryEntry> allMaps = MapRegistry.getInstance().getMaps();
        if (allMaps.isEmpty()) {
            Wathe.LOGGER.info("No Wathe maps registered, skipping map voting");
            return;
        }

        this.votingActive = true;
        this.votingTicksRemaining = VOTING_DURATION_TICKS;
        this.selectedMapIndex = -1;
        this.roulettePhase = false;
        this.rouletteTicksRemaining = 0;
        this.playerVotes.clear();
        this.availableMaps.clear();
        this.unavailableMaps.clear();

        List<MapRegistryEntry> eligible = new ArrayList<>();
        for (MapRegistryEntry mapEntry : allMaps.values()) {
            if (mapEntry.isEligible(playerCount)) {
                eligible.add(mapEntry);
            } else {
                String reason = playerCount < mapEntry.minPlayers()
                        ? "min_players:" + mapEntry.minPlayers()
                        : "max_players:" + mapEntry.maxPlayers();
                this.unavailableMaps.add(new UnavailableMapEntry(mapEntry.dimensionId(), mapEntry.displayName(), reason));
            }
        }

        List<MapRegistryEntry> selectedCandidates = selectRandomCandidates(eligible);
        for (MapRegistryEntry mapEntry : eligible) {
            if (!selectedCandidates.contains(mapEntry)) {
                this.unavailableMaps.add(new UnavailableMapEntry(mapEntry.dimensionId(), mapEntry.displayName(), "random_excluded"));
            }
        }
        for (MapRegistryEntry mapEntry : selectedCandidates) {
            this.availableMaps.add(new VotingMapEntry(
                    mapEntry.dimensionId(),
                    mapEntry.displayName(),
                    mapEntry.description().orElse(""),
                    mapEntry.minPlayers(),
                    mapEntry.maxPlayers()
            ));
        }

        this.voteCounts = new int[this.availableMaps.size()];

        if (this.availableMaps.isEmpty()) {
            Wathe.LOGGER.info("No eligible Wathe maps for {} players, map voting cancelled", playerCount);
            this.votingActive = false;
            this.sync();
            return;
        }

        if (this.availableMaps.size() == 1) {
            Identifier targetDimension = this.availableMaps.getFirst().dimensionId();
            this.lastSelectedDimension = targetDimension;
            this.votingActive = false;
            this.sync();
            GameFunctions.finalizeVoting(server.getOverworld(), targetDimension);
            return;
        }

        Wathe.LOGGER.info("Wathe map voting started with {} candidates, {} unavailable, {} online players",
                availableMaps.size(), unavailableMaps.size(), playerCount);
        this.sync();
    }

    private List<MapRegistryEntry> selectRandomCandidates(List<MapRegistryEntry> eligible) {
        if (randomMapCount <= 0 || randomMapCount >= eligible.size()) {
            return new ArrayList<>(eligible);
        }

        // 每次开始投票都重新洗牌，所以 randommapcount 的候选不会跨轮固定。
        List<MapRegistryEntry> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled, new Random());
        return new ArrayList<>(shuffled.subList(0, randomMapCount));
    }

    public void castVote(ServerPlayerEntity player, int mapIndex) {
        if (onlyOpVoting && !isOperator(player)) {
            return;
        }
        castVote(player.getUuid(), mapIndex);
    }

    public void castVote(UUID playerId, int mapIndex) {
        if (!votingActive || roulettePhase) return;
        if (mapIndex < 0 || mapIndex >= availableMaps.size()) return;

        Integer oldVote = playerVotes.get(playerId);
        if (oldVote != null && oldVote >= 0 && oldVote < voteCounts.length) {
            voteCounts[oldVote] = Math.max(0, voteCounts[oldVote] - 1);
        }

        playerVotes.put(playerId, mapIndex);
        voteCounts[mapIndex]++;

        int onlinePlayers = getOnlinePlayerCount();
        if (onlinePlayers > 0 && playerVotes.size() >= onlinePlayers && votingTicksRemaining > ALL_VOTED_REMAINING_TICKS) {
            votingTicksRemaining = ALL_VOTED_REMAINING_TICKS;
        }

        this.sync();
    }

    private boolean isOperator(ServerPlayerEntity player) {
        MinecraftServer playerServer = player.getServer();
        return playerServer != null && playerServer.getPermissionLevel(player.getGameProfile()) >= 2;
    }

    private int getOnlinePlayerCount() {
        if (server == null) return 0;
        int onlinePlayers = 0;
        for (ServerWorld world : server.getWorlds()) {
            onlinePlayers += world.getPlayers().size();
        }
        return onlinePlayers;
    }

    private void endVoting() {
        if (server == null || availableMaps.isEmpty()) return;

        this.selectedMapIndex = selectMapWeighted();
        this.roulettePhase = true;
        this.rouletteTicksRemaining = ROULETTE_DURATION_TICKS;
        this.sync();
    }

    private int selectMapWeighted() {
        if (availableMaps.isEmpty()) return -1;

        Random random = new Random();
        boolean hasAnyVotes = false;
        for (int count : voteCounts) {
            if (count > 0) {
                hasAnyVotes = true;
                break;
            }
        }

        int totalWeight = 0;
        int[] weights = new int[availableMaps.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = hasAnyVotes ? voteCounts[i] : 1;
            totalWeight += weights[i];
        }

        int roll = random.nextInt(Math.max(1, totalWeight));
        int cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return i;
            }
        }
        return 0;
    }

    private void finishSelection() {
        if (server == null) return;
        if (selectedMapIndex < 0 || selectedMapIndex >= availableMaps.size()) {
            Wathe.LOGGER.warn("Invalid selected Wathe map index {}, aborting map voting result", selectedMapIndex);
            reset();
            return;
        }

        Identifier targetDimensionId = availableMaps.get(selectedMapIndex).dimensionId();
        this.lastSelectedDimension = targetDimensionId;
        this.votingActive = false;
        this.sync();

        GameFunctions.finalizeVoting(server.getOverworld(), targetDimensionId);
    }

    public void reset() {
        this.votingActive = false;
        this.votingTicksRemaining = 0;
        this.availableMaps.clear();
        this.unavailableMaps.clear();
        this.voteCounts = new int[0];
        this.playerVotes.clear();
        this.selectedMapIndex = -1;
        this.roulettePhase = false;
        this.rouletteTicksRemaining = 0;
        this.sync();
    }

    public void onPlayerJoin() {
        if (votingActive && !roulettePhase) {
            this.sync();
        }
    }

    @Nullable
    public ServerWorld getLastSelectedWorld() {
        if (server == null || lastSelectedDimension == null) {
            return null;
        }
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, lastSelectedDimension);
        return server.getWorld(worldKey);
    }

    @Override
    public void serverTick() {
        if (!votingActive) return;

        if (roulettePhase) {
            if (--rouletteTicksRemaining <= 0) {
                finishSelection();
            } else if (rouletteTicksRemaining % 20 == 0) {
                this.sync();
            }
            return;
        }

        if (--votingTicksRemaining <= 0) {
            endVoting();
        } else if (votingTicksRemaining % 20 == 0) {
            this.sync();
        }
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.votingActive = tag.getBoolean("VotingActive");
        this.votingTicksRemaining = tag.getInt("VotingTicksRemaining");
        this.selectedMapIndex = tag.getInt("SelectedMapIndex");
        this.roulettePhase = tag.getBoolean("RoulettePhase");
        this.rouletteTicksRemaining = tag.getInt("RouletteTicksRemaining");
        this.onlyOpVoting = tag.getBoolean("OnlyOpVoting");
        this.randomMapCount = tag.getInt("RandomMapCount");

        if (tag.contains("LastSelectedDimension")) {
            this.lastSelectedDimension = Identifier.tryParse(tag.getString("LastSelectedDimension"));
        } else {
            this.lastSelectedDimension = null;
        }

        this.availableMaps.clear();
        if (tag.contains("AvailableMaps")) {
            NbtList mapsList = tag.getList("AvailableMaps", NbtElement.COMPOUND_TYPE);
            for (NbtElement element : mapsList) {
                NbtCompound mapNbt = (NbtCompound) element;
                Identifier dimensionId = Identifier.tryParse(mapNbt.getString("DimensionId"));
                if (dimensionId != null) {
                    this.availableMaps.add(new VotingMapEntry(
                            dimensionId,
                            mapNbt.getString("DisplayName"),
                            mapNbt.getString("Description"),
                            mapNbt.getInt("MinPlayers"),
                            mapNbt.getInt("MaxPlayers")
                    ));
                }
            }
        }

        this.unavailableMaps.clear();
        if (tag.contains("UnavailableMaps")) {
            NbtList unavailableList = tag.getList("UnavailableMaps", NbtElement.COMPOUND_TYPE);
            for (NbtElement element : unavailableList) {
                NbtCompound mapNbt = (NbtCompound) element;
                Identifier dimensionId = Identifier.tryParse(mapNbt.getString("DimensionId"));
                if (dimensionId != null) {
                    this.unavailableMaps.add(new UnavailableMapEntry(
                            dimensionId,
                            mapNbt.getString("DisplayName"),
                            mapNbt.getString("Reason")
                    ));
                }
            }
        }

        this.voteCounts = tag.contains("VoteCounts") ? tag.getIntArray("VoteCounts") : new int[this.availableMaps.size()];

        this.playerVotes.clear();
        if (tag.contains("PlayerVotes")) {
            NbtList votesList = tag.getList("PlayerVotes", NbtElement.COMPOUND_TYPE);
            for (NbtElement element : votesList) {
                NbtCompound voteNbt = (NbtCompound) element;
                if (voteNbt.contains("PlayerId")) {
                    this.playerVotes.put(voteNbt.getUuid("PlayerId"), voteNbt.getInt("MapIndex"));
                }
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putBoolean("VotingActive", votingActive);
        tag.putInt("VotingTicksRemaining", votingTicksRemaining);
        tag.putInt("SelectedMapIndex", selectedMapIndex);
        tag.putBoolean("RoulettePhase", roulettePhase);
        tag.putInt("RouletteTicksRemaining", rouletteTicksRemaining);
        tag.putBoolean("OnlyOpVoting", onlyOpVoting);
        tag.putInt("RandomMapCount", randomMapCount);

        if (lastSelectedDimension != null) {
            tag.putString("LastSelectedDimension", lastSelectedDimension.toString());
        }

        NbtList mapsList = new NbtList();
        for (VotingMapEntry entry : availableMaps) {
            NbtCompound mapNbt = new NbtCompound();
            mapNbt.putString("DimensionId", entry.dimensionId().toString());
            mapNbt.putString("DisplayName", entry.displayName());
            mapNbt.putString("Description", entry.description());
            mapNbt.putInt("MinPlayers", entry.minPlayers());
            mapNbt.putInt("MaxPlayers", entry.maxPlayers());
            mapsList.add(mapNbt);
        }
        tag.put("AvailableMaps", mapsList);

        NbtList unavailableList = new NbtList();
        for (UnavailableMapEntry entry : unavailableMaps) {
            NbtCompound mapNbt = new NbtCompound();
            mapNbt.putString("DimensionId", entry.dimensionId().toString());
            mapNbt.putString("DisplayName", entry.displayName());
            mapNbt.putString("Reason", entry.reason());
            unavailableList.add(mapNbt);
        }
        tag.put("UnavailableMaps", unavailableList);

        tag.putIntArray("VoteCounts", voteCounts);

        NbtList votesList = new NbtList();
        for (Map.Entry<UUID, Integer> entry : playerVotes.entrySet()) {
            NbtCompound voteNbt = new NbtCompound();
            voteNbt.putUuid("PlayerId", entry.getKey());
            voteNbt.putInt("MapIndex", entry.getValue());
            votesList.add(voteNbt);
        }
        tag.put("PlayerVotes", votesList);
    }
}
