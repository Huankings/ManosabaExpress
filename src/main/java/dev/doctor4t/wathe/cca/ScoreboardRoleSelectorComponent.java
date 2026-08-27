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
     * 权重计算的基础锚点。
     *
     * <p>这里的权重不是直接百分比，而是“抽签票数”：某个玩家最终概率 =
     * 该玩家权重 / 本轮所有候选人的权重总和。例如 4 个候选人权重分别是
     * 2.0、1.0、1.0、1.0，那么第一个人的概率大约是 2 / 5 = 40%。</p>
     *
     * <p>无历史、关闭权重系统、或者调试覆写失效时，都回到这个值。
     * 之所以保留 1.0，是为了让“新人默认值”和“正常历史值”在同一条尺度上比较：
     * 新人不是天然更高，也不是天然更低，而是作为后续回升/回压的基准线。</p>
     *
     * <p>调高这个值：新人、无历史玩家、关闭权重时的候选人都会更有竞争力，
     * 历史回升带来的相对优势会被稀释。例如 DEFAULT_WEIGHT 从 1.0 改成 2.0 后，
     * “缺席 3 局稀缺阵营”的玩家权重会从 2.8 变成 3.8，但新人也从 1.0 变成 2.0，
     * 老玩家相对新人的优势会从 2.8 倍降到 1.9 倍左右。</p>
     *
     * <p>调低这个值：历史回升会显得更强，老玩家更容易压过新人；
     * 但如果太低，新玩家或者刚清空权重的玩家会比较难抽到稀缺阵营。
     * 一般建议把这个值固定为 1.0，只调下面的回升和回压参数。</p>
     */
    private static final double DEFAULT_WEIGHT = 1.0D;

    /**
     * 权重最终输出的安全边界。
     *
     * <p>MIN_ASSIGNMENT_WEIGHT 不是为了继续把人压到接近 0，而是为了保底：
     * 再怎么倒霉，也不应该低到让某个玩家几乎永远抽不到目标阵营。
     * 调高它会削弱连续重复惩罚，已经连续当过杀手/中立的玩家仍会保留更多机会；
     * 调低它会更强地压住重复分配，但太低会让某些玩家长期几乎没有翻身机会。
     * 例如 0.25 表示再低也保留四分之一张基础票，通常比 0.05 这种“接近消失”的保底更温和。</p>
     *
     * <p>MAX_ASSIGNMENT_WEIGHT 则是为了防止历史加分无限膨胀。
     * 我们希望“老玩家明显高于新人”，但不希望某个玩家的历史把整张概率表撑坏。
     * 调高它会让特别久没拿到某阵营/职业的玩家更容易形成压倒性优势；
     * 调低它会让概率更平滑，但也会削弱长期没体验到稀缺阵营的补偿。
     * 例如某玩家累计算出 22.0，当前上限 16.0 会把它压成 16.0；
     * 如果上限调到 8.0，这类长期缺席玩家就只能拿到 8.0，追赶力度会明显变弱。</p>
     *
     * <p>MAX_DEBUG_WEIGHT 只给管理员手动设权重时用，方便测试，不影响正常抽取上限。
     * 调高它只会让 /roleWeights set 之类调试指令能设置更夸张的权重；
     * 调低它会限制管理员压测极端概率。正常开局自动计算仍会被 MAX_ASSIGNMENT_WEIGHT 裁剪。</p>
     */
    private static final double MIN_ASSIGNMENT_WEIGHT = 0.05D;
    private static final double MAX_DEBUG_WEIGHT = 10_000.0D;
    private static final double MAX_ASSIGNMENT_WEIGHT = 12.0D;

    /**
     * 阵营历史的“回升”参数。
     *
     * <p>这里分成稀缺阵营和普通阵营两档：
     * - 稀缺阵营：杀手 / 中立，回升更快，避免新人默认 1.0 直接把老玩家顶掉；
     * - 普通阵营：平民 / 义警，回升稍慢，保持整体分配更平滑。</p>
     *
     * <p>RECOVERY_STEP 表示前几把没拿到目标阵营时，每一把补多少权重。
     * 调高它会让老玩家更快超过新人，更适合“几局没当杀手/中立就应该明显优先”的服务器；
     * 调低它会让分配更接近纯随机，但也更容易再次出现新人默认 1.0 压过老玩家的情况。
     * 例子：稀缺阵营前期步长 0.60 时，连续 3 局没当杀手/中立的玩家，
     * 阵营回升前的基础权重是 1.0 + 3 * 0.60 = 2.80，已经明显高于新人 1.0。</p>
     *
     * <p>LATE_RECOVERY_STEP 表示超过窗口后，每多缺席一局继续补多少，通常比 RECOVERY_STEP 小。
     * 调高它会让“特别久没拿到某阵营”的玩家继续快速堆高，适合强补偿；
     * 调低它会让玩家过了快速回升区后趋于平缓，避免老玩家长期缺席后直接碾压整张概率表。
     * 例子：稀缺阵营窗口 4、前期步长 0.60、后期步长 0.24 时，连续 6 局没当杀手/中立，
     * 权重是 1.0 + 4 * 0.60 + 2 * 0.24 = 3.88。</p>
     *
     * <p>RECOVERY_WINDOW 表示前几把属于“快速回升区”，超过这个窗口后进入“缓慢追赶区”。
     * 调高它会让 RECOVERY_STEP 这种大步长持续更多局，老玩家追赶更快；
     * 调低它会更早切到 LATE_RECOVERY_STEP，追赶更稳但更慢。
     * 例子：稀缺阵营窗口从 4 改成 2 时，连续 6 局缺席会变成
     * 1.0 + 2 * 0.60 + 4 * 0.24 = 3.16，比当前 3.88 更保守。</p>
     */
    private static final double SCARCE_FACTION_RECOVERY_STEP = 0.60D;
    private static final double COMMON_FACTION_RECOVERY_STEP = 0.35D;
    private static final double SCARCE_FACTION_LATE_RECOVERY_STEP = 0.24D;
    private static final double COMMON_FACTION_LATE_RECOVERY_STEP = 0.15D;
    private static final int SCARCE_FACTION_RECOVERY_WINDOW = 4;
    private static final int COMMON_FACTION_RECOVERY_WINDOW = 3;

    /**
     * 阵营历史的“回压”参数。
     *
     * <p>RECOVERY_STEP 负责把长期没拿到目标阵营的玩家往上抬，
     * OVERUSE_PENALTY 和 STREAK_PENALTY 则负责把“已经拿得太多”或“连续拿同一阵营”的玩家往回压。
     * 这样做的目的，是让权重既能回升到比新人更高，又不会一路堆到特别夸张。</p>
     *
     * <p>OVERUSE_PENALTY 处理“总次数比候选人里的最低次数多多少”。
     * 公式是 weight / (1 + 超出次数 * penalty)。调高它会更强烈照顾从没拿过或拿得较少的人；
     * 调低它会更允许历史上已经拿过多次的人继续参与竞争。
     * 例子：某玩家稀缺阵营回升后是 3.88，但比当前候选人的最低稀缺阵营次数多 2 次，
     * 当前 0.28 会压成 3.88 / (1 + 2 * 0.28) 约等于 2.49。它仍高于新人 1.0，
     * 但不会因为连续缺席几局就完全无视“以前已经当过很多次”的事实。</p>
     *
     * <p>STREAK_PENALTY 处理“上一局或连续多局就是这个阵营”的情况。
     * 调高它会更强地阻止连局重复，尤其适合杀手/中立这种稀缺阵营；
     * 调低它会让背靠背抽到同阵营更常见。
     * 例子：玩家上一局刚当杀手，稀缺阵营 streak 至少按 1 计算，
     * 当前 0.75 会把权重除以 1.75；如果连续 2 局都是杀手，则除以 2.5，
     * 基本就会把“继续杀手”的概率压得很低。</p>
     */
    private static final double SCARCE_FACTION_OVERUSE_PENALTY = 0.22D;
    private static final double COMMON_FACTION_OVERUSE_PENALTY = 0.15D;
    private static final double SCARCE_FACTION_STREAK_PENALTY = 0.75D;
    private static final double COMMON_FACTION_STREAK_PENALTY = 0.42D;

    /**
     * 具体职业历史的“回升”参数。
     *
     * <p>这组参数和阵营历史同理，但力度略低于阵营历史：
     * - 阵营层面先保证“谁更缺某阵营”；
     * - 职业层面再保证“谁更缺某个具体职业”。</p>
     *
     * <p>稀缺阵营下的具体职业回升仍然更强，因为杀手 / 中立本身就更稀有。
     * 调高 ROLE_RECOVERY_STEP 会让某个具体职业更快轮到没玩过它的人；
     * 调低它会让“阵营公平”占主导，具体职业内部更随机。
     * 例子：某玩家已经 3 局没抽到目标稀缺职业，职业层会额外给
     * 3 * 0.36 = 1.08；如果同样 3 局没抽到普通职业，则额外给 3 * 0.24 = 0.72。</p>
     *
     * <p>ROLE_LATE_RECOVERY_STEP 和 ROLE_RECOVERY_WINDOW 的含义与阵营参数一致：
     * 前几局用较大的快速回升，超过窗口后用较小的后期回升。
     * 调高后期步长会让特别久没玩某个职业的人持续追高；
     * 调低后期步长会让它更像“温和提醒”，避免具体职业权重盖过阵营权重。
     * 例子：稀缺职业连续 6 局没拿到时，职业层回升是
     * 4 * 0.36 + 2 * 0.16 = 1.76；普通职业同样 6 局没拿到则是
     * 3 * 0.24 + 3 * 0.10 = 1.02。</p>
     */
    private static final double SCARCE_ROLE_RECOVERY_STEP = 0.36D;
    private static final double COMMON_ROLE_RECOVERY_STEP = 0.24D;
    private static final double SCARCE_ROLE_LATE_RECOVERY_STEP = 0.16D;
    private static final double COMMON_ROLE_LATE_RECOVERY_STEP = 0.10D;
    private static final int SCARCE_ROLE_RECOVERY_WINDOW = 4;
    private static final int COMMON_ROLE_RECOVERY_WINDOW = 3;

    /**
     * 具体职业历史的“回压”参数。
     *
     * <p>如果某个玩家已经频繁抽到某个具体扩展职业，就会被逐步往回压，
     * 防止“同一个职业总是塞给同一个人”的情况再次出现。</p>
     *
     * <p>ROLE_OVERUSE_PENALTY 处理“这个具体职业总次数偏多”的情况。
     * 调高它会让职业更平均地分散到不同玩家身上；
     * 调低它会允许某些玩家更频繁重复拿到同一个职业。
     * 例子：某玩家目标职业回升后权重是 3.0，但该职业次数比候选人最低值多 2 次，
     * 稀缺职业 0.18 会压成 3.0 / (1 + 2 * 0.18) 约等于 2.21；
     * 普通职业 0.12 则约等于 2.42，说明普通职业的回压更温和。</p>
     *
     * <p>ROLE_STREAK_PENALTY 处理“连续同一个具体职业”的情况。
     * 调高它会更强地避免连续两局同职业，适合非常有记忆点或强度较高的扩展职业；
     * 调低它会让同职业连局更可能发生。
     * 例子：玩家上一局刚是某个稀缺职业，本局又参与该职业候选时，
     * 当前 0.45 会把职业层权重除以 1.45；如果连续 2 局都是同职业，则除以 1.90。</p>
     */
    private static final double SCARCE_ROLE_OVERUSE_PENALTY = 0.28D;
    private static final double COMMON_ROLE_OVERUSE_PENALTY = 0.22D;
    private static final double SCARCE_ROLE_STREAK_PENALTY = 0.65D;
    private static final double COMMON_ROLE_STREAK_PENALTY = 0.50D;

    /**
     * 新版职业权重账本在 NBT 里的键名。
     *
     * <p>如果以后要做兼容迁移或排查存档内容，先看这个 key。
     * 旧版只存 killerRounds / vigilanteRounds，新版会多写完整账本。</p>
     */
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
            int factionRounds = record.getFactionRounds(targetFaction);
            int factionMissedRounds = Math.max(0, record.getParticipatedRounds() - factionRounds);
            int factionExcess = Math.max(0, factionRounds - minimumFactionRounds);
            int factionStreak = record.getLastFaction() == targetFaction ? Math.max(1, record.getConsecutiveFactionRounds()) : 0;
            double recoveryStep = scarceFaction ? SCARCE_FACTION_RECOVERY_STEP : COMMON_FACTION_RECOVERY_STEP;
            double lateRecoveryStep = scarceFaction ? SCARCE_FACTION_LATE_RECOVERY_STEP : COMMON_FACTION_LATE_RECOVERY_STEP;
            int recoveryWindow = scarceFaction ? SCARCE_FACTION_RECOVERY_WINDOW : COMMON_FACTION_RECOVERY_WINDOW;
            double overusePenalty = scarceFaction ? SCARCE_FACTION_OVERUSE_PENALTY : COMMON_FACTION_OVERUSE_PENALTY;
            double streakPenalty = scarceFaction ? SCARCE_FACTION_STREAK_PENALTY : COMMON_FACTION_STREAK_PENALTY;
            /*
             * 阵营历史是公平性的第一层。
             *
             * 旧算法是一路做指数衰减，结果历史越久的玩家越接近 0.0x，
             * 新玩家默认 1.0 反而会直接压过他们。现在改成“恢复加成优先、过量回压辅助”：
             * - 好几把没拿到对应阵营时，先把权重抬上来；
             * - 拿得过多时，再做软惩罚；
             * - 连续同阵营再叠一层回压，避免连局重复。
             */
            weight = addRecoveryWeight(weight, factionMissedRounds, recoveryStep, lateRecoveryStep, recoveryWindow);
            weight = applySoftPenalty(weight, factionExcess, overusePenalty);
            weight = applySoftPenalty(weight, factionStreak, streakPenalty);
        }

        if (includeRoleHistory && targetRole != null) {
            Identifier roleId = targetRole.identifier();
            int roleRounds = record.getRoleRounds(roleId);
            int roleMissedRounds = Math.max(0, record.getParticipatedRounds() - roleRounds);
            int roleExcess = Math.max(0, roleRounds - minimumRoleRounds);
            int roleStreak = roleId.equals(record.getLastRole()) ? Math.max(1, record.getConsecutiveRoleRounds()) : 0;
            double roleRecoveryStep = scarceFaction ? SCARCE_ROLE_RECOVERY_STEP : COMMON_ROLE_RECOVERY_STEP;
            double roleLateRecoveryStep = scarceFaction ? SCARCE_ROLE_LATE_RECOVERY_STEP : COMMON_ROLE_LATE_RECOVERY_STEP;
            int roleRecoveryWindow = scarceFaction ? SCARCE_ROLE_RECOVERY_WINDOW : COMMON_ROLE_RECOVERY_WINDOW;
            double roleOverusePenalty = scarceFaction ? SCARCE_ROLE_OVERUSE_PENALTY : COMMON_ROLE_OVERUSE_PENALTY;
            double roleStreakPenalty = scarceFaction ? SCARCE_ROLE_STREAK_PENALTY : COMMON_ROLE_STREAK_PENALTY;
            /*
             * 职业历史是第二层。
             *
             * 这里也同样采用“缺席越久越加分、过量越多越回压”的方式。
             * 这样既能避免某一个扩展职业总落到同一个人身上，
             * 也能让老玩家在几把没拿到该职业后逐步抬回 1.0 以上，不会被新玩家默认值碾压。
             */
            weight = addRecoveryWeight(weight, roleMissedRounds, roleRecoveryStep, roleLateRecoveryStep, roleRecoveryWindow);
            weight = applySoftPenalty(weight, roleExcess, roleOverusePenalty);
            weight = applySoftPenalty(weight, roleStreak, roleStreakPenalty);
        }

        return clampAssignmentWeight(weight);
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

    /**
     * 按“前期快速回升 + 后期缓慢回升”给权重加分。
     *
     * <p>missedRounds 是玩家参与了多少局却没有拿到目标阵营/职业。
     * 前 recoveryWindow 局使用 recoveryStep，超过窗口后使用 lateRecoveryStep。
     * 这和上方常量注释里的例子一致：稀缺阵营缺席 6 局时，
     * 会先算 4 * 0.60，再算 2 * 0.24。</p>
     */
    private static double addRecoveryWeight(double weight, int missedRounds, double recoveryStep, double lateRecoveryStep, int recoveryWindow) {
        if (missedRounds <= 0) {
            return weight;
        }
        int earlyRounds = Math.min(missedRounds, recoveryWindow);
        weight += earlyRounds * recoveryStep;
        if (missedRounds > recoveryWindow) {
            weight += (missedRounds - recoveryWindow) * lateRecoveryStep;
        }
        return weight;
    }

    /**
     * 对“拿得过多”或“连续重复”的情况做软回压。
     *
     * <p>这里没有直接乘一个很小的衰减值，而是使用
     * weight / (1 + rounds * penaltyPerRound)。
     * 好处是惩罚会随着次数增加而变强，但不会轻易把玩家压到 0 附近；
     * 例如 3.88 权重、2 次超额、0.28 惩罚，会得到约 2.49。</p>
     */
    private static double applySoftPenalty(double weight, int overuseRounds, double penaltyPerRound) {
        if (overuseRounds <= 0) {
            return weight;
        }
        return weight / (1.0D + overuseRounds * penaltyPerRound);
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
