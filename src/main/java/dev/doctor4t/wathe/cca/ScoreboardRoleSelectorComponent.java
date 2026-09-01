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

    /**
     * 无历史、关闭权重或调试覆盖无效时使用的基础抽签票数。
     *
     * <p>最终概率 = 当前玩家票数 / 本阶段所有候选人的票数总和。例如 2.0、1.0、1.0
     * 三名候选人时，第一名约有 50% 概率。调高该值会整体抬高无历史玩家，
     * 使新人更接近纯随机；调低该值会放大历史缺口差异，但新人翻身会变慢。</p>
     *
     * <p>当前值 1.0 表示新玩家从中性基准开始，不额外偏爱或压制任何人。</p>
     */
    private static final double DEFAULT_WEIGHT = 1.0D;

    /**
     * 自动权重的最小票数。调高会减轻连续重复后的冷却，整体更随机；调低会更强地
     * 避免刚拿过目标阵营的玩家再次被抽中，但过低会造成长期冷落。
     * 例：当前 0.35 时，即使多层冷却叠加，玩家仍保留至少基础票数的 35%。
     */
    private static final double MIN_ASSIGNMENT_WEIGHT = 0.35D;
    /** 管理员调试覆盖允许的最大票数；只影响 /roleWeights set，不影响自动算法上限。 */
    private static final double MAX_DEBUG_WEIGHT = 10_000.0D;
    /** 自动算法最大票数。调高会强化长期缺口补偿，调低会让全体概率更接近均匀。 */
    private static final double MAX_ASSIGNMENT_WEIGHT = 3.5D;

    /**
     * 每局结束后有效历史保留的比例。当前 0.975 的数学半衰期约为 27 局。
     * 调高会让长期玩家记忆更久、分配更稳定；调低会更快遗忘，适合人员流动大的服务器。
     * 例如连续 27 局不再获得的历史会约剩一半，而不是永久累积。
     */
    private static final double HISTORY_DECAY_PER_ROUND = 0.975D;
    /**
     * 新玩家伪历史轮数。它给新人一个温和先验，避免无历史玩家凭 1.0 基准票压过老玩家。
     * 调高会让新人更接近均匀随机；调低会增强老玩家缺口优势。
     * 例如值为 4 时，新玩家按“参与过 4 局但尚未偏向任何阵营”参与计算。
     */
    private static final double PRIOR_PARTICIPATION_ROUNDS = 4.0D;
    /**
     * 缺口指数温度。公式近似为 exp(缺口 / 温度)。调高会压平玩家间差距，调低会更照顾欠缺者。
     * 例如缺口为 1.35 时，温度 1.35 产生 e 倍票数；温度改为 2.70 后只产生约 1.65 倍。
     */
    private static final double DEFICIT_TEMPERATURE = 1.35D;
    /**
     * 连续同阵营/职业的短期冷却强度。调高会更少出现连局重复，调低会更接近完全随机。
     * 例如强度 0.28、连续 2 局时会乘 exp(-0.56)，约保留 57% 票数；它不会永久累积。
     */
    private static final double STREAK_COOLDOWN_STRENGTH = 0.28D;
    /**
     * 杀手与中立共享稀缺压力强度。调高会更强地限制“杀手+中立”总次数过高者，调低则只看各自份额。
     * 例如多出 2 个稀缺阵营暴露、强度 0.22 时，额外因子约为 exp(-0.44)=0.64。
     */
    private static final double SHARED_SCARCE_PRESSURE_STRENGTH = 0.22D;
    /**
     * 回归玩家补偿上限。调高会让久未上线玩家回归时更容易获得缺席阵营，调低可防止回归即高概率稀缺。
     * 例如当前 0.45 表示回归补偿最多把最终票数提高 45%，不会按离线天数无限增长。
     */
    private static final double RETURNING_PLAYER_BONUS_CAP = 0.45D;
    /** NBT 数据版本，用于未来继续调整字段时识别迁移格式。 */
    private static final int WEIGHT_DATA_VERSION = 2;
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
     * 权重开关属于 scoreboard，而不是某一个维度的 GameWorldComponent。
     * 这样所有地图、所有维度和 Harpy/Wathe 两种模式读取的是同一个全局状态。
     */
    private boolean weightsEnabled = true;
    /** 全服已经开始过的分配轮次，用于历史衰减和回归玩家识别。 */
    private long assignmentRound = 0L;

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

    public boolean areWeightsEnabled() {
        return this.weightsEnabled;
    }

    public void setWeightsEnabled(boolean enabled) {
        this.weightsEnabled = enabled;
    }

    public long getAssignmentRound() {
        return this.assignmentRound;
    }

    /**
     * 在真正抽取职业前推进一次全局轮次，并衰减所有玩家的有效历史。
     * 原始整数次数仍保留用于管理员查看和旧存档兼容，只有参与计算的有效暴露值衰减。
     */
    public void beginAssignmentRound() {
        this.assignmentRound++;
        for (RoleWeightRecord record : this.roleWeightRecords.values()) {
            record.decay(HISTORY_DECAY_PER_ROUND, this.assignmentRound);
        }
    }

    public int reset() {
        this.killerRounds.clear();
        this.vigilanteRounds.clear();
        this.roleWeightRecords.clear();
        this.assignmentRound = 0L;
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

        /*
         * 一次性计算本阶段权重，再做不放回抽样。
         * 旧实现每抽中一人就重新计算“候选人最低次数”，会让第二个名额依赖第一个
         * 名额的结果，形成单局内的路径依赖和轮轴感。固定本阶段权重只移除已选玩家，
         * 仍然保留随机性，但不再改变比较基准。
         */
        LinkedHashMap<ServerPlayerEntity, Double> weights = getAssignmentWeights(gameComponent, remaining, targetFaction, targetRole, count, includeFactionHistory, includeRoleHistory);
        for (int i = 0; i < count; i++) {
            double total = weights.entrySet().stream()
                    .filter(entry -> remaining.contains(entry.getKey()))
                    .mapToDouble(Map.Entry::getValue)
                    .sum();
            ServerPlayerEntity picked = null;

            if (total > 0.0D) {
                double random = world.getRandom().nextDouble() * total;
                for (Map.Entry<ServerPlayerEntity, Double> entry : weights.entrySet()) {
                    if (!remaining.contains(entry.getKey())) {
                        continue;
                    }
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
        return getAssignmentWeights(gameComponent, candidates, targetFaction, targetRole,
                candidates.isEmpty() ? 0 : 1, includeFactionHistory, includeRoleHistory);
    }

    private LinkedHashMap<ServerPlayerEntity, Double> getAssignmentWeights(@NotNull GameWorldComponent gameComponent,
                                                                            @NotNull List<ServerPlayerEntity> candidates,
                                                                            @NotNull Faction targetFaction,
                                                                            @Nullable Role targetRole,
                                                                            int desiredCount,
                                                                            boolean includeFactionHistory,
                                                                            boolean includeRoleHistory) {
        LinkedHashMap<ServerPlayerEntity, Double> weights = new LinkedHashMap<>();
        double targetRate = candidates.isEmpty() ? 0.0D : Math.min(1.0D, Math.max(0.0D, (double) desiredCount / candidates.size()));

        for (ServerPlayerEntity player : candidates) {
            weights.put(player, calculateAssignmentWeight(player, gameComponent, targetFaction, targetRole,
                    includeFactionHistory, includeRoleHistory, targetRate));
        }
        return weights;
    }

    public double getAssignmentWeight(@NotNull ServerPlayerEntity player,
                                      @NotNull GameWorldComponent gameComponent,
                                      @NotNull Faction targetFaction,
                                      @Nullable Role targetRole,
                                      boolean includeFactionHistory,
                                      boolean includeRoleHistory) {
        return calculateAssignmentWeight(player, gameComponent, targetFaction, targetRole,
                includeFactionHistory, includeRoleHistory, 1.0D);
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
            getOrCreateRoleWeightRecord(player).recordAssignment(player, role, this.assignmentRound);
        }
        syncLegacyFactionCounters();
    }

    private double calculateAssignmentWeight(@NotNull ServerPlayerEntity player,
                                             @NotNull GameWorldComponent gameComponent,
                                             @NotNull Faction targetFaction,
                                             @Nullable Role targetRole,
                                             boolean includeFactionHistory,
                                             boolean includeRoleHistory,
                                             double targetRate) {
        if (!this.weightsEnabled) {
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
        double opportunity = record.getEffectiveParticipatedRounds() + PRIOR_PARTICIPATION_ROUNDS;
        double returningBonus = record.getRoundsSinceParticipation(this.assignmentRound) > 1
                ? Math.min(RETURNING_PLAYER_BONUS_CAP, (record.getRoundsSinceParticipation(this.assignmentRound) - 1) * 0.05D)
                : 0.0D;

        if (includeFactionHistory) {
            double observed = record.getEffectiveFactionRounds(targetFaction) + PRIOR_PARTICIPATION_ROUNDS * targetRate;
            double deficit = opportunity * targetRate - observed;
            weight *= Math.exp(clampDeficit(deficit) / DEFICIT_TEMPERATURE);

            if (scarceFaction) {
                double hostileRate = 2.0D / Math.max(2.0D, gameComponent.getKillerDividend());
                double hostileObserved = record.getEffectiveFactionRounds(Faction.KILLER)
                        + record.getEffectiveFactionRounds(Faction.NEUTRAL);
                double hostileExpected = opportunity * hostileRate;
                double hostileExcess = Math.max(0.0D, hostileObserved - hostileExpected);
                weight *= Math.exp(-Math.min(2.0D, hostileExcess * SHARED_SCARCE_PRESSURE_STRENGTH));
            }

            if (record.getLastFaction() == targetFaction) {
                weight *= Math.exp(-Math.min(1.5D, record.getConsecutiveFactionRounds() * STREAK_COOLDOWN_STRENGTH));
            }
        }

        if (includeRoleHistory && targetRole != null) {
            Identifier roleId = targetRole.identifier();
            double observed = record.getEffectiveRoleRounds(roleId) + PRIOR_PARTICIPATION_ROUNDS * targetRate;
            double deficit = opportunity * targetRate - observed;
            weight *= Math.exp(clampDeficit(deficit) / DEFICIT_TEMPERATURE);
            if (roleId.equals(record.getLastRole())) {
                weight *= Math.exp(-Math.min(1.5D, record.getConsecutiveRoleRounds() * STREAK_COOLDOWN_STRENGTH));
            }
        }

        return clampAssignmentWeight(weight * (1.0D + returningBonus));
    }

    private static double sanitizeDebugWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) {
            return DEFAULT_WEIGHT;
        }
        return Math.max(0.0D, Math.min(weight, MAX_DEBUG_WEIGHT));
    }

    /**
     * 自动计算出来的开局权重最终裁剪。
     *
     * <p>调试指令的手动覆写走 {@link #sanitizeDebugWeight(double)}，可以放到更高；
     * 正常开局算法走这里，保证不会低于 MIN_ASSIGNMENT_WEIGHT，也不会高于 MAX_ASSIGNMENT_WEIGHT。</p>
     */
    private static double clampAssignmentWeight(double weight) {
        if (Double.isNaN(weight) || Double.isInfinite(weight)) {
            return DEFAULT_WEIGHT;
        }
        return Math.max(MIN_ASSIGNMENT_WEIGHT, Math.min(weight, MAX_ASSIGNMENT_WEIGHT));
    }

    /** 防止指数函数在极端历史数据或管理员调试数据下溢出/溢出。 */
    private static double clampDeficit(double deficit) {
        if (Double.isNaN(deficit) || Double.isInfinite(deficit)) {
            return 0.0D;
        }
        return Math.max(-6.0D, Math.min(deficit, 6.0D));
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
        tag.putInt("WeightDataVersion", WEIGHT_DATA_VERSION);
        tag.putBoolean("WeightsEnabled", this.weightsEnabled);
        tag.putLong("AssignmentRound", this.assignmentRound);

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
        this.weightsEnabled = !tag.contains("WeightsEnabled") || tag.getBoolean("WeightsEnabled");
        this.assignmentRound = Math.max(0L, tag.getLong("AssignmentRound"));
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
        /** 参与次数和分配次数的衰减副本，整数 map 继续用于旧命令和人工审计。 */
        private final EnumMap<Faction, Double> effectiveFactionRounds = new EnumMap<>(Faction.class);
        private final HashMap<Identifier, Double> effectiveRoleRounds = new HashMap<>();
        private final EnumMap<Faction, Double> factionWeightOverrides = new EnumMap<>(Faction.class);
        private final HashMap<Identifier, Double> roleWeightOverrides = new HashMap<>();

        private String lastKnownName = "";
        private int participatedRounds = 0;
        private double effectiveParticipatedRounds = 0.0D;
        private long lastParticipationRound = -1L;
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

        public double getEffectiveParticipatedRounds() {
            return this.effectiveParticipatedRounds;
        }

        public double getEffectiveFactionRounds(@NotNull Faction faction) {
            return this.effectiveFactionRounds.getOrDefault(faction, 0.0D);
        }

        public double getEffectiveRoleRounds(@NotNull Identifier roleId) {
            return this.effectiveRoleRounds.getOrDefault(roleId, 0.0D);
        }

        public long getLastParticipationRound() {
            return this.lastParticipationRound;
        }

        public long getRoundsSinceParticipation(long currentRound) {
            if (this.lastParticipationRound < 0L || currentRound <= this.lastParticipationRound) {
                return 0L;
            }
            return currentRound - this.lastParticipationRound;
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

        private void decay(double factor, long currentRound) {
            this.effectiveParticipatedRounds *= factor;
            for (Faction faction : Faction.values()) {
                this.effectiveFactionRounds.computeIfPresent(faction, (ignored, value) -> value * factor);
            }
            this.effectiveRoleRounds.replaceAll((ignored, value) -> value * factor);
            /* 离开一局后，上一局连续状态不应继续惩罚回归玩家。 */
            if (this.lastParticipationRound >= 0L && currentRound - this.lastParticipationRound > 1L) {
                this.consecutiveFactionRounds = 0;
                this.consecutiveRoleRounds = 0;
            }
        }

        private void recordAssignment(@NotNull ServerPlayerEntity player, @NotNull Role role, long currentRound) {
            updateLastKnownName(player);
            this.participatedRounds++;
            this.effectiveParticipatedRounds += 1.0D;
            this.lastParticipationRound = currentRound;

            Faction faction = Objects.requireNonNull(role.getFaction(), "role faction");
            Identifier roleId = role.identifier();

            this.factionRounds.put(faction, getFactionRounds(faction) + 1);
            this.roleRounds.put(roleId, getRoleRounds(roleId) + 1);
            this.effectiveFactionRounds.put(faction, getEffectiveFactionRounds(faction) + 1.0D);
            this.effectiveRoleRounds.put(roleId, getEffectiveRoleRounds(roleId) + 1.0D);

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
                this.effectiveFactionRounds.remove(faction);
            } else {
                this.factionRounds.put(faction, times);
                this.effectiveFactionRounds.put(faction, (double) times);
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
            compound.putDouble("effectiveParticipatedRounds", this.effectiveParticipatedRounds);
            compound.putLong("lastParticipationRound", this.lastParticipationRound);
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

            NbtList effectiveFactionCounts = new NbtList();
            for (Map.Entry<Faction, Double> entry : this.effectiveFactionRounds.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("faction", entry.getKey().name());
                entryNbt.putDouble("value", entry.getValue());
                effectiveFactionCounts.add(entryNbt);
            }
            compound.put("effectiveFactionCounts", effectiveFactionCounts);

            NbtList roleCounts = new NbtList();
            for (Map.Entry<Identifier, Integer> entry : this.roleRounds.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("role", entry.getKey().toString());
                entryNbt.putInt("times", entry.getValue());
                roleCounts.add(entryNbt);
            }
            compound.put("roleCounts", roleCounts);

            NbtList effectiveRoleCounts = new NbtList();
            for (Map.Entry<Identifier, Double> entry : this.effectiveRoleRounds.entrySet()) {
                NbtCompound entryNbt = new NbtCompound();
                entryNbt.putString("role", entry.getKey().toString());
                entryNbt.putDouble("value", entry.getValue());
                effectiveRoleCounts.add(entryNbt);
            }
            compound.put("effectiveRoleCounts", effectiveRoleCounts);

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
            record.effectiveParticipatedRounds = compound.contains("effectiveParticipatedRounds")
                    ? Math.max(0.0D, compound.getDouble("effectiveParticipatedRounds"))
                    : record.participatedRounds;
            record.lastParticipationRound = compound.contains("lastParticipationRound")
                    ? Math.max(-1L, compound.getLong("lastParticipationRound"))
                    : -1L;
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
                        record.effectiveFactionRounds.put(faction, (double) times);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 跳过未知阵营，避免旧/坏 NBT 阻止服务器启动。
                }
            }

            if (compound.contains("effectiveFactionCounts")) {
                for (NbtElement element : compound.getList("effectiveFactionCounts", NbtElement.COMPOUND_TYPE)) {
                    NbtCompound entryNbt = (NbtCompound) element;
                    try {
                        Faction faction = Faction.valueOf(entryNbt.getString("faction"));
                        double value = entryNbt.getDouble("value");
                        if (!Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0D) {
                            record.effectiveFactionRounds.put(faction, value);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 跳过未知阵营，避免坏数据阻止服务器启动。
                    }
                }
            }

            for (NbtElement element : compound.getList("roleCounts", NbtElement.COMPOUND_TYPE)) {
                NbtCompound entryNbt = (NbtCompound) element;
                Identifier roleId = Identifier.tryParse(entryNbt.getString("role"));
                int times = Math.max(0, entryNbt.getInt("times"));
                if (roleId != null && times > 0) {
                    record.roleRounds.put(roleId, times);
                    record.effectiveRoleRounds.put(roleId, (double) times);
                }
            }

            if (compound.contains("effectiveRoleCounts")) {
                for (NbtElement element : compound.getList("effectiveRoleCounts", NbtElement.COMPOUND_TYPE)) {
                    NbtCompound entryNbt = (NbtCompound) element;
                    Identifier roleId = Identifier.tryParse(entryNbt.getString("role"));
                    double value = entryNbt.getDouble("value");
                    if (roleId != null && !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0D) {
                        record.effectiveRoleRounds.put(roleId, value);
                    }
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
