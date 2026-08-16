package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

public class ScoreboardRoleSelectorComponent implements AutoSyncedComponent {
    public static final ComponentKey<ScoreboardRoleSelectorComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("rolecounter"), ScoreboardRoleSelectorComponent.class);

    private static final double DEFAULT_WEIGHT = 1.0D;
    private static final double MAX_DEBUG_WEIGHT = 10_000.0D;
    private static final String ROLE_WEIGHT_RECORDS_KEY = "RoleWeightRecords";

    public final Scoreboard scoreboard;
    public final MinecraftServer server;

    /**
     * 旧版 Wathe 只分别记录“当过杀手/义警几次”。
     *
     * <p>这两个 map 继续保留，是为了兼容旧存档和旧调试指令；
     * 新算法真正使用的是 {@link #roleWeightRecords}。每次读写 NBT 时会把旧字段同步成新账本里的
     * 杀手/义警阵营次数，避免旧世界升级后丢失已有权重。</p>
     */
    public final Map<UUID, Integer> killerRounds = new HashMap<>();
    public final Map<UUID, Integer> vigilanteRounds = new HashMap<>();
    public final List<UUID> forcedKillers = new ArrayList<>();
    public final List<UUID> forcedVigilantes = new ArrayList<>();

    /**
     * 新版职业分配权重账本。
     *
     * <p>它按玩家 UUID 保存阵营次数、具体职业次数、上一局职业以及管理员手动设置的调试权重。
     * 这份数据挂在 scoreboard 组件上，而不是世界组件上，目的是跨地图/维度复用同一套历史，
     * 同时离线玩家也能继续保存权重，等下一次回服时继续参与公平分配。</p>
     */
    private final Map<UUID, RoleWeightRecord> roleWeightRecords = new HashMap<>();

    public ScoreboardRoleSelectorComponent(Scoreboard scoreboard, @Nullable MinecraftServer server) {
        this.scoreboard = scoreboard;
        this.server = server;
    }

    public int reset() {
        this.killerRounds.clear();
        this.vigilanteRounds.clear();
        this.roleWeightRecords.clear();
        return 1;
    }

    public void resetAllWeights() {
        reset();
    }

    public void resetWeights(@NotNull UUID uuid) {
        this.killerRounds.remove(uuid);
        this.vigilanteRounds.remove(uuid);
        this.roleWeightRecords.remove(uuid);
    }

    public void resetWeights(@NotNull Collection<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            resetWeights(player.getUuid());
        }
    }

    public int resetStoredOfflineWeights(@NotNull Collection<ServerPlayerEntity> onlinePlayers) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity player : onlinePlayers) {
            online.add(player.getUuid());
        }

        ArrayList<UUID> removed = new ArrayList<>();
        for (UUID uuid : this.roleWeightRecords.keySet()) {
            if (!online.contains(uuid)) {
                removed.add(uuid);
            }
        }
        for (UUID uuid : removed) {
            resetWeights(uuid);
        }
        return removed.size();
    }

    public Map<UUID, RoleWeightRecord> getRoleWeightRecords() {
        return Collections.unmodifiableMap(this.roleWeightRecords);
    }

    public Set<UUID> getKnownWeightPlayers() {
        return Collections.unmodifiableSet(this.roleWeightRecords.keySet());
    }

    public @Nullable RoleWeightRecord getRoleWeightRecord(@NotNull UUID uuid) {
        return this.roleWeightRecords.get(uuid);
    }

    public RoleWeightRecord getOrCreateRoleWeightRecord(@NotNull UUID uuid) {
        return this.roleWeightRecords.computeIfAbsent(uuid, ignored -> new RoleWeightRecord());
    }

    public RoleWeightRecord getOrCreateRoleWeightRecord(@NotNull ServerPlayerEntity player) {
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player.getUuid());
        record.updateLastKnownName(player);
        return record;
    }

    public void setFactionWeightOverride(@NotNull ServerPlayerEntity player, @NotNull Faction faction, double weight) {
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
        record.setFactionWeightOverride(faction, sanitizeDebugWeight(weight));
    }

    public void setRoleWeightOverride(@NotNull ServerPlayerEntity player, @NotNull Role role, double weight) {
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
        record.setRoleWeightOverride(role.identifier(), sanitizeDebugWeight(weight));
    }

    public void clearWeightOverrides(@NotNull ServerPlayerEntity player) {
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
        record.clearWeightOverrides();
    }

    /**
     * 旧命令入口仍保留，但现在它写入的是“杀手阵营历史次数”。
     *
     * <p>这样服务器里已有的脚本或管理员习惯不用立刻改命令，同时新算法也能读到同一份数据。</p>
     */
    public void setKillerRounds(@NotNull ServerCommandSource source, @NotNull ServerPlayerEntity player, int times) {
        times = Math.max(0, times);
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
        record.setFactionRounds(Faction.KILLER, times);
        syncLegacyFactionCounters();
        int finalTimes = times;
        source.sendMessage(Text.literal("Set ").formatted(Formatting.GRAY)
                .append(player.getDisplayName().copy().formatted(Formatting.YELLOW))
                .append(Text.literal("'s Killer rounds to ").formatted(Formatting.GRAY))
                .append(Text.literal("%d".formatted(finalTimes)).withColor(0x808080))
                .append(Text.literal(".").formatted(Formatting.GRAY)));
    }

    public void setVigilanteRounds(@NotNull ServerCommandSource source, @NotNull ServerPlayerEntity player, int times) {
        times = Math.max(0, times);
        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
        record.setFactionRounds(Faction.VIGILANTE, times);
        syncLegacyFactionCounters();
        int finalTimes = times;
        source.sendMessage(Text.literal("Set ").formatted(Formatting.GRAY)
                .append(player.getDisplayName().copy().formatted(Formatting.YELLOW))
                .append(Text.literal("'s Vigilante rounds to ").formatted(Formatting.GRAY))
                .append(Text.literal("%d".formatted(finalTimes)).withColor(0x808080))
                .append(Text.literal(".").formatted(Formatting.GRAY)));
    }

    /**
     * 查询当前世界在线玩家在几个主要阵营上的实时抽取概率。
     *
     * <p>这里刻意使用 {@code sendMessage}，不走 {@code sendFeedback}，
     * 这样即使服务器关闭了 command feedback，管理员也能稳定看到完整权重报告。</p>
     */
    public void checkWeights(@NotNull ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(world);
        List<ServerPlayerEntity> players = world.getPlayers();

        LinkedHashMap<ServerPlayerEntity, Double> killerWeights = getAssignmentWeights(gameWorld, players, Faction.KILLER, WatheRoles.KILLER, true, true);
        LinkedHashMap<ServerPlayerEntity, Double> vigilanteWeights = getAssignmentWeights(gameWorld, players, Faction.VIGILANTE, WatheRoles.VIGILANTE, true, true);
        LinkedHashMap<ServerPlayerEntity, Double> neutralWeights = getAssignmentWeights(gameWorld, players, Faction.NEUTRAL, null, true, false);

        MutableText text = Text.literal("Role Weights: ").formatted(Formatting.GRAY)
                .append(Text.literal(gameWorld.areWeightsEnabled() ? "enabled" : "disabled").formatted(gameWorld.areWeightsEnabled() ? Formatting.GREEN : Formatting.RED));
        for (ServerPlayerEntity player : players) {
            RoleWeightRecord record = getOrCreateRoleWeightRecord(player);
            text.append("\n").append(player.getDisplayName());
            text.append(formatFactionLine(Faction.KILLER, record, percentageOf(killerWeights, player)));
            text.append(formatFactionLine(Faction.VIGILANTE, record, percentageOf(vigilanteWeights, player)));
            text.append(formatFactionLine(Faction.NEUTRAL, record, percentageOf(neutralWeights, player)));
        }

        source.sendMessage(text);
    }

    public int assignKillers(ServerWorld world, GameWorldComponent gameComponent, @NotNull List<ServerPlayerEntity> players, int killerCount) {
        ArrayList<UUID> killers = new ArrayList<>();
        ArrayList<ServerPlayerEntity> candidates = new ArrayList<>(players);

        for (UUID uuid : this.forcedKillers) {
            PlayerEntity player = world.getPlayerByUuid(uuid);
            if (player instanceof ServerPlayerEntity serverPlayer && candidates.remove(serverPlayer)) {
                killers.add(uuid);
                killerCount--;
            }
        }
        this.forcedKillers.clear();

        for (ServerPlayerEntity player : selectWeightedPlayers(world, gameComponent, candidates, killerCount, Faction.KILLER, WatheRoles.KILLER, true, true)) {
            killers.add(player.getUuid());
        }

        for (UUID killerUUID : killers) {
            gameComponent.addRole(killerUUID, WatheRoles.KILLER);
            PlayerEntity killer = world.getPlayerByUuid(killerUUID);
            if (killer != null) {
                PlayerShopComponent.KEY.get(killer).setBalance(GameConstants.MONEY_START);
            }
        }
        return killers.size();
    }

    public void assignVigilantes(ServerWorld world, GameWorldComponent gameComponent, @NotNull List<ServerPlayerEntity> players, int vigilanteCount) {
        ArrayList<ServerPlayerEntity> vigilantes = new ArrayList<>();
        ArrayList<ServerPlayerEntity> candidates = new ArrayList<>(players);

        for (UUID uuid : this.forcedVigilantes) {
            PlayerEntity player = world.getPlayerByUuid(uuid);
            if (player instanceof ServerPlayerEntity serverPlayer
                    && candidates.remove(serverPlayer)
                    && !gameComponent.canUseKillerFeatures(player)) {
                /*
                 * 这里只负责给玩家占用一个“原版义警位”，
                 * 不再在分配阶段直接发左轮。
                 *
                 * 这样 HarpyModLoader 后续如果要把这个原版义警位替换成扩展义警职业，
                 * 就不会连带把左轮也一起塞给扩展义警。
                 * 最终只有“对局开始后仍然保留原版 WatheRoles.VIGILANTE 身份”的玩家，
                 * 才会在后续统一补发左轮。
                 */
                gameComponent.addRole(player, WatheRoles.VIGILANTE);
                vigilanteCount--;
            }
        }
        this.forcedVigilantes.clear();

        candidates.removeIf(player -> gameComponent.isRole(player, WatheRoles.KILLER));
        vigilantes.addAll(selectWeightedPlayers(world, gameComponent, candidates, vigilanteCount, Faction.VIGILANTE, WatheRoles.VIGILANTE, true, true));

        for (ServerPlayerEntity player : vigilantes) {
            gameComponent.addRole(player, WatheRoles.VIGILANTE);
        }
    }

    /**
     * 只给“最终仍然保持原版义警职业”的玩家发左轮。
     *
     * <p>这一步刻意放在整个义警位分配结束之后：
     * 1. 原版 wathe 模式下，没有扩展义警替换，原版义警仍会正常拿到左轮；
     * 2. 扩展模组若把原版义警位替换成了别的义警职业，则该玩家不再满足
     *    {@link WatheRoles#VIGILANTE} 判定，因此不会自动拿左轮；
     * 3. 以后新增的其他义警阵营职业也能沿用同一规则，自行决定起始武器。</p>
     */
    public static void giveRevolversToVanillaVigilantes(@NotNull GameWorldComponent gameComponent, @NotNull List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity player : players) {
            if (gameComponent.isRole(player, WatheRoles.VIGILANTE)) {
                player.giveItemStack(new ItemStack(WatheItems.REVOLVER));
            }
        }
    }

    /**
     * 按当前权重从候选玩家里抽取若干名玩家。
     *
     * <p>这个方法同时给 Wathe 原版阵营位和 Harpy 扩展职业替换池使用。
     * includeFactionHistory 负责“某阵营不要总落到同一批人身上”，
     * includeRoleHistory 负责“某个具体扩展职业不要连续落到同一个人身上”。</p>
     */
    public List<ServerPlayerEntity> selectWeightedPlayers(@NotNull World world,
                                                          @NotNull GameWorldComponent gameComponent,
                                                          @NotNull List<ServerPlayerEntity> candidates,
                                                          int desiredCount,
                                                          @NotNull Faction targetFaction,
                                                          @Nullable Role targetRole,
                                                          boolean includeFactionHistory,
                                                          boolean includeRoleHistory) {
        ArrayList<ServerPlayerEntity> remaining = new ArrayList<>(candidates);
        ArrayList<ServerPlayerEntity> selected = new ArrayList<>();
        int count = Math.min(Math.max(0, desiredCount), remaining.size());

        for (int i = 0; i < count; i++) {
            LinkedHashMap<ServerPlayerEntity, Double> weights = getAssignmentWeights(gameComponent, remaining, targetFaction, targetRole, includeFactionHistory, includeRoleHistory);
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            ServerPlayerEntity picked = null;

            if (total > 0.0D) {
                double random = world.getRandom().nextDouble() * total;
                for (Map.Entry<ServerPlayerEntity, Double> entry : weights.entrySet()) {
                    random -= entry.getValue();
                    if (random <= 0.0D) {
                        picked = entry.getKey();
                        break;
                    }
                }
            }

            if (picked == null && !remaining.isEmpty()) {
                picked = remaining.get(world.getRandom().nextInt(remaining.size()));
            }

            if (picked == null) {
                break;
            }
            selected.add(picked);
            remaining.remove(picked);
        }
        return selected;
    }

    public LinkedHashMap<ServerPlayerEntity, Double> getAssignmentWeights(@NotNull GameWorldComponent gameComponent,
                                                                          @NotNull List<ServerPlayerEntity> candidates,
                                                                          @NotNull Faction targetFaction,
                                                                          @Nullable Role targetRole,
                                                                          boolean includeFactionHistory,
                                                                          boolean includeRoleHistory) {
        LinkedHashMap<ServerPlayerEntity, Double> weights = new LinkedHashMap<>();
        int minimumFactionRounds = getMinimumFactionRounds(candidates, targetFaction);
        int minimumRoleRounds = targetRole == null ? 0 : getMinimumRoleRounds(candidates, targetRole.identifier());

        for (ServerPlayerEntity player : candidates) {
            weights.put(player, calculateAssignmentWeight(player, gameComponent, targetFaction, targetRole, includeFactionHistory, includeRoleHistory, minimumFactionRounds, minimumRoleRounds));
        }
        return weights;
    }

    public double getAssignmentWeight(@NotNull ServerPlayerEntity player,
                                      @NotNull GameWorldComponent gameComponent,
                                      @NotNull Faction targetFaction,
                                      @Nullable Role targetRole,
                                      boolean includeFactionHistory,
                                      boolean includeRoleHistory) {
        return calculateAssignmentWeight(player, gameComponent, targetFaction, targetRole, includeFactionHistory, includeRoleHistory, 0, 0);
    }

    /**
     * 在所有开局初始化监听跑完后记录最终职业。
     *
     * <p>HarpyModLoader 会先拿到 Wathe 原版杀手/义警位，再把这些位替换成扩展职业；
     * 其他扩展也可能在 ON_FINISH_INITIALIZE 里补发或转换身份。因此权重不能在“抽中原版位”的瞬间写死，
     * 必须等本局最终职业稳定后统一记录，才能同时覆盖原版职业和扩展职业。</p>
     */
    public void recordRoundAssignments(@NotNull ServerWorld world, @NotNull List<ServerPlayerEntity> players, @NotNull GameWorldComponent gameComponent) {
        for (ServerPlayerEntity player : players) {
            Role role = gameComponent.getRole(player);
            if (role == null) {
                continue;
            }
            getOrCreateRoleWeightRecord(player).recordAssignment(player, role);
        }
        syncLegacyFactionCounters();
    }

    private double calculateAssignmentWeight(@NotNull ServerPlayerEntity player,
                                             @NotNull GameWorldComponent gameComponent,
                                             @NotNull Faction targetFaction,
                                             @Nullable Role targetRole,
                                             boolean includeFactionHistory,
                                             boolean includeRoleHistory,
                                             int minimumFactionRounds,
                                             int minimumRoleRounds) {
        if (!gameComponent.areWeightsEnabled()) {
            return DEFAULT_WEIGHT;
        }

        RoleWeightRecord record = getOrCreateRoleWeightRecord(player);

        if (includeRoleHistory && targetRole != null) {
            OptionalDouble override = record.getRoleWeightOverride(targetRole.identifier());
            if (override.isPresent()) {
                return sanitizeDebugWeight(override.getAsDouble());
            }
        }
        if (includeFactionHistory) {
            OptionalDouble override = record.getFactionWeightOverride(targetFaction);
            if (override.isPresent()) {
                return sanitizeDebugWeight(override.getAsDouble());
            }
        }

        double weight = DEFAULT_WEIGHT;
        boolean scarceFaction = targetFaction == Faction.KILLER || targetFaction == Faction.NEUTRAL;

        if (includeFactionHistory) {
            int factionExcess = Math.max(0, record.getFactionRounds(targetFaction) - minimumFactionRounds);
            /*
             * 阵营历史是公平性的第一层：同一个玩家已经多次拿到稀缺阵营时，
             * 下一次仍然允许被抽到，但概率会被明显压低。
             */
            weight *= Math.pow(scarceFaction ? 0.42D : 0.62D, factionExcess);

            if (record.getLastFaction() == targetFaction) {
                int streak = Math.max(1, record.getConsecutiveFactionRounds());
                /*
                 * 连续同阵营是玩家体感最明显的问题，尤其是杀手/中立。
                 * 这里使用强力软惩罚，而不是直接禁用，避免小人数调试局因为候选人太少无法分配。
                 */
                weight *= Math.pow(scarceFaction ? 0.18D : 0.35D, Math.min(streak, 4));
            } else {
                int missedRounds = Math.max(0, record.getParticipatedRounds() - record.getFactionRounds(targetFaction));
                weight *= 1.0D + Math.min(missedRounds, 6) * (scarceFaction ? 0.18D : 0.08D);
            }
        }

        if (includeRoleHistory && targetRole != null) {
            Identifier roleId = targetRole.identifier();
            int roleExcess = Math.max(0, record.getRoleRounds(roleId) - minimumRoleRounds);
            /*
             * 职业历史是第二层：即使某玩家这局合理地拿到了杀手/义警位，
             * Harpy 再替换具体扩展职业时也会尽量避开“同一个扩展职业连着给同一个人”的情况。
             */
            weight *= Math.pow(0.52D, roleExcess);

            if (roleId.equals(record.getLastRole())) {
                int streak = Math.max(1, record.getConsecutiveRoleRounds());
                weight *= Math.pow(0.16D, Math.min(streak, 4));
            }
        }

        return Math.max(0.0001D, Math.min(weight, MAX_DEBUG_WEIGHT));
    }

    private int getMinimumFactionRounds(@NotNull List<ServerPlayerEntity> candidates, @NotNull Faction faction) {
        if (candidates.isEmpty()) {
            return 0;
        }
        int minimum = Integer.MAX_VALUE;
        for (ServerPlayerEntity player : candidates) {
            RoleWeightRecord record = this.roleWeightRecords.get(player.getUuid());
            minimum = Math.min(minimum, record == null ? 0 : record.getFactionRounds(faction));
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    private int getMinimumRoleRounds(@NotNull List<ServerPlayerEntity> candidates, @NotNull Identifier roleId) {
        if (candidates.isEmpty()) {
            return 0;
        }
        int minimum = Integer.MAX_VALUE;
        for (ServerPlayerEntity player : candidates) {
            RoleWeightRecord record = this.roleWeightRecords.get(player.getUuid());
            minimum = Math.min(minimum, record == null ? 0 : record.getRoleRounds(roleId));
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    private static double sanitizeDebugWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) {
            return DEFAULT_WEIGHT;
        }
        return Math.max(0.0D, Math.min(weight, MAX_DEBUG_WEIGHT));
    }

    private void syncLegacyFactionCounters() {
        this.killerRounds.clear();
        this.vigilanteRounds.clear();
        for (Map.Entry<UUID, RoleWeightRecord> entry : this.roleWeightRecords.entrySet()) {
            int killer = entry.getValue().getFactionRounds(Faction.KILLER);
            int vigilante = entry.getValue().getFactionRounds(Faction.VIGILANTE);
            if (killer > 0) {
                this.killerRounds.put(entry.getKey(), killer);
            }
            if (vigilante > 0) {
                this.vigilanteRounds.put(entry.getKey(), vigilante);
            }
        }
    }

    private void migrateLegacyRoundCounters() {
        for (Map.Entry<UUID, Integer> entry : this.killerRounds.entrySet()) {
            getOrCreateRoleWeightRecord(entry.getKey()).setFactionRounds(Faction.KILLER, Math.max(0, entry.getValue()));
        }
        for (Map.Entry<UUID, Integer> entry : this.vigilanteRounds.entrySet()) {
            getOrCreateRoleWeightRecord(entry.getKey()).setFactionRounds(Faction.VIGILANTE, Math.max(0, entry.getValue()));
        }
    }

    private static double percentageOf(@NotNull Map<ServerPlayerEntity, Double> weights, @NotNull ServerPlayerEntity player) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0.0D) {
            return 0.0D;
        }
        return weights.getOrDefault(player, 0.0D) / total * 100.0D;
    }

    private static MutableText formatFactionLine(@NotNull Faction faction, @NotNull RoleWeightRecord record, double percentage) {
        return Text.literal("\n  ")
                .append(Text.literal(faction.name().toLowerCase(Locale.ROOT)).withColor(faction.displayColor()))
                .append(Text.literal(" (").formatted(Formatting.GRAY))
                .append(Text.literal("%d".formatted(record.getFactionRounds(faction))).withColor(0x808080))
                .append(Text.literal("): ").formatted(Formatting.GRAY))
                .append(Text.literal("%.2f%%".formatted(percentage)).withColor(0x808080));
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        syncLegacyFactionCounters();

        NbtList killerRounds = new NbtList();
        for (Map.Entry<UUID, Integer> detail : this.killerRounds.entrySet()) {
            NbtCompound compound = new NbtCompound();
            compound.putUuid("uuid", detail.getKey());
            compound.putInt("times", detail.getValue());
            killerRounds.add(compound);
        }
        tag.put("killerRounds", killerRounds);

        NbtList vigilanteRounds = new NbtList();
        for (Map.Entry<UUID, Integer> detail : this.vigilanteRounds.entrySet()) {
            NbtCompound compound = new NbtCompound();
            compound.putUuid("uuid", detail.getKey());
            compound.putInt("times", detail.getValue());
            vigilanteRounds.add(compound);
        }
        tag.put("vigilanteRounds", vigilanteRounds);

        NbtList records = new NbtList();
        for (Map.Entry<UUID, RoleWeightRecord> entry : this.roleWeightRecords.entrySet()) {
            NbtCompound recordNbt = entry.getValue().toNbt();
            recordNbt.putUuid("uuid", entry.getKey());
            records.add(recordNbt);
        }
        tag.put(ROLE_WEIGHT_RECORDS_KEY, records);
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.killerRounds.clear();
        for (NbtElement element : tag.getList("killerRounds", NbtElement.COMPOUND_TYPE)) {
            NbtCompound compound = (NbtCompound) element;
            if (!compound.contains("uuid") || !compound.contains("times")) {
                continue;
            }
            this.killerRounds.put(compound.getUuid("uuid"), compound.getInt("times"));
        }

        this.vigilanteRounds.clear();
        for (NbtElement element : tag.getList("vigilanteRounds", NbtElement.COMPOUND_TYPE)) {
            NbtCompound compound = (NbtCompound) element;
            if (!compound.contains("uuid") || !compound.contains("times")) {
                continue;
            }
            this.vigilanteRounds.put(compound.getUuid("uuid"), compound.getInt("times"));
        }

        this.roleWeightRecords.clear();
        if (tag.contains(ROLE_WEIGHT_RECORDS_KEY)) {
            for (NbtElement element : tag.getList(ROLE_WEIGHT_RECORDS_KEY, NbtElement.COMPOUND_TYPE)) {
                NbtCompound compound = (NbtCompound) element;
                if (!compound.contains("uuid")) {
                    continue;
                }
                this.roleWeightRecords.put(compound.getUuid("uuid"), RoleWeightRecord.fromNbt(compound));
            }
            syncLegacyFactionCounters();
        } else {
            migrateLegacyRoundCounters();
        }
    }

    public static final class RoleWeightRecord {
        private final EnumMap<Faction, Integer> factionRounds = new EnumMap<>(Faction.class);
        private final HashMap<Identifier, Integer> roleRounds = new HashMap<>();
        private final EnumMap<Faction, Double> factionWeightOverrides = new EnumMap<>(Faction.class);
        private final HashMap<Identifier, Double> roleWeightOverrides = new HashMap<>();

        private String lastKnownName = "";
        private int participatedRounds = 0;
        private @Nullable Faction lastFaction = null;
        private @Nullable Identifier lastRole = null;
        private int consecutiveFactionRounds = 0;
        private int consecutiveRoleRounds = 0;

        public String getLastKnownName() {
            return this.lastKnownName;
        }

        public int getParticipatedRounds() {
            return this.participatedRounds;
        }

        public @Nullable Faction getLastFaction() {
            return this.lastFaction;
        }

        public @Nullable Identifier getLastRole() {
            return this.lastRole;
        }

        public int getConsecutiveFactionRounds() {
            return this.consecutiveFactionRounds;
        }

        public int getConsecutiveRoleRounds() {
            return this.consecutiveRoleRounds;
        }

        public int getFactionRounds(@NotNull Faction faction) {
            return this.factionRounds.getOrDefault(faction, 0);
        }

        public int getRoleRounds(@NotNull Identifier roleId) {
            return this.roleRounds.getOrDefault(roleId, 0);
        }

        public Map<Faction, Integer> getFactionRoundsView() {
            return Collections.unmodifiableMap(this.factionRounds);
        }

        public Map<Identifier, Integer> getRoleRoundsView() {
            return Collections.unmodifiableMap(this.roleRounds);
        }

        public Map<Faction, Double> getFactionWeightOverridesView() {
            return Collections.unmodifiableMap(this.factionWeightOverrides);
        }

        public Map<Identifier, Double> getRoleWeightOverridesView() {
            return Collections.unmodifiableMap(this.roleWeightOverrides);
        }

        public OptionalDouble getFactionWeightOverride(@NotNull Faction faction) {
            Double override = this.factionWeightOverrides.get(faction);
            return override == null ? OptionalDouble.empty() : OptionalDouble.of(override);
        }

        public OptionalDouble getRoleWeightOverride(@NotNull Identifier roleId) {
            Double override = this.roleWeightOverrides.get(roleId);
            return override == null ? OptionalDouble.empty() : OptionalDouble.of(override);
        }

        private void updateLastKnownName(@NotNull ServerPlayerEntity player) {
            this.lastKnownName = player.getGameProfile().getName();
        }

        private void recordAssignment(@NotNull ServerPlayerEntity player, @NotNull Role role) {
            updateLastKnownName(player);
            this.participatedRounds++;

            Faction faction = Objects.requireNonNull(role.getFaction(), "role faction");
            Identifier roleId = role.identifier();

            this.factionRounds.put(faction, getFactionRounds(faction) + 1);
            this.roleRounds.put(roleId, getRoleRounds(roleId) + 1);

            if (this.lastFaction == faction) {
                this.consecutiveFactionRounds++;
            } else {
                this.lastFaction = faction;
                this.consecutiveFactionRounds = 1;
            }

            if (roleId.equals(this.lastRole)) {
                this.consecutiveRoleRounds++;
            } else {
                this.lastRole = roleId;
                this.consecutiveRoleRounds = 1;
            }
        }

        private void setFactionRounds(@NotNull Faction faction, int times) {
            if (times <= 0) {
                this.factionRounds.remove(faction);
            } else {
                this.factionRounds.put(faction, times);
            }
        }

        private void setFactionWeightOverride(@NotNull Faction faction, double weight) {
            this.factionWeightOverrides.put(faction, weight);
        }

        private void setRoleWeightOverride(@NotNull Identifier roleId, double weight) {
            this.roleWeightOverrides.put(roleId, weight);
        }

        private void clearWeightOverrides() {
            this.factionWeightOverrides.clear();
            this.roleWeightOverrides.clear();
        }

        private NbtCompound toNbt() {
            NbtCompound compound = new NbtCompound();
            compound.putString("lastKnownName", this.lastKnownName);
            compound.putInt("participatedRounds", this.participatedRounds);
            if (this.lastFaction != null) {
                compound.putString("lastFaction", this.lastFaction.name());
            }
            if (this.lastRole != null) {
                compound.putString("lastRole", this.lastRole.toString());
            }
            compound.putInt("consecutiveFactionRounds", this.consecutiveFactionRounds);
            compound.putInt("consecutiveRoleRounds", this.consecutiveRoleRounds);

            NbtList factionCounts = new NbtList();
            for (Map.Entry<Faction, Integer> entry : this.factionRounds.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("faction", entry.getKey().name());
                entryNbt.putInt("times", entry.getValue());
                factionCounts.add(entryNbt);
            }
            compound.put("factionCounts", factionCounts);

            NbtList roleCounts = new NbtList();
            for (Map.Entry<Identifier, Integer> entry : this.roleRounds.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("role", entry.getKey().toString());
                entryNbt.putInt("times", entry.getValue());
                roleCounts.add(entryNbt);
            }
            compound.put("roleCounts", roleCounts);

            NbtList factionOverrides = new NbtList();
            for (Map.Entry<Faction, Double> entry : this.factionWeightOverrides.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("faction", entry.getKey().name());
                entryNbt.putDouble("weight", entry.getValue());
                factionOverrides.add(entryNbt);
            }
            compound.put("factionOverrides", factionOverrides);

            NbtList roleOverrides = new NbtList();
            for (Map.Entry<Identifier, Double> entry : this.roleWeightOverrides.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("role", entry.getKey().toString());
                entryNbt.putDouble("weight", entry.getValue());
                roleOverrides.add(entryNbt);
            }
            compound.put("roleOverrides", roleOverrides);

            return compound;
        }

        private static RoleWeightRecord fromNbt(@NotNull NbtCompound compound) {
            RoleWeightRecord record = new RoleWeightRecord();
            record.lastKnownName = compound.getString("lastKnownName");
            record.participatedRounds = Math.max(0, compound.getInt("participatedRounds"));
            if (compound.contains("lastFaction")) {
                try {
                    record.lastFaction = Faction.valueOf(compound.getString("lastFaction"));
                } catch (IllegalArgumentException ignored) {
                    record.lastFaction = null;
                }
            }
            if (compound.contains("lastRole")) {
                record.lastRole = Identifier.tryParse(compound.getString("lastRole"));
            }
            record.consecutiveFactionRounds = Math.max(0, compound.getInt("consecutiveFactionRounds"));
            record.consecutiveRoleRounds = Math.max(0, compound.getInt("consecutiveRoleRounds"));

            for (NbtElement element : compound.getList("factionCounts", NbtElement.COMPOUND_TYPE)) {
                NbtCompound entryNbt = (NbtCompound) element;
                try {
                    Faction faction = Faction.valueOf(entryNbt.getString("faction"));
                    int times = Math.max(0, entryNbt.getInt("times"));
                    if (times > 0) {
                        record.factionRounds.put(faction, times);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 跳过未知阵营，避免旧/坏 NBT 阻止服务器启动。
                }
            }

            for (NbtElement element : compound.getList("roleCounts", NbtElement.COMPOUND_TYPE)) {
                NbtCompound entryNbt = (NbtCompound) element;
                Identifier roleId = Identifier.tryParse(entryNbt.getString("role"));
                int times = Math.max(0, entryNbt.getInt("times"));
                if (roleId != null && times > 0) {
                    record.roleRounds.put(roleId, times);
                }
            }

            for (NbtElement element : compound.getList("factionOverrides", NbtElement.COMPOUND_TYPE)) {
                NbtCompound entryNbt = (NbtCompound) element;
                try {
                    Faction faction = Faction.valueOf(entryNbt.getString("faction"));
                    record.factionWeightOverrides.put(faction, sanitizeDebugWeight(entryNbt.getDouble("weight")));
                } catch (IllegalArgumentException ignored) {
                    // 跳过未知阵营，避免旧/坏 NBT 阻止服务器启动。
                }
            }

            for (NbtElement element : compound.getList("roleOverrides", NbtElement.COMPOUND_TYPE)) {
                NbtCompound entryNbt = (NbtCompound) element;
                Identifier roleId = Identifier.tryParse(entryNbt.getString("role"));
                if (roleId != null) {
                    record.roleWeightOverrides.put(roleId, sanitizeDebugWeight(entryNbt.getDouble("weight")));
                }
            }

            return record;
        }
    }
}
