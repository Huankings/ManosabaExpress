package dev.doctor4t.wathe.api.sync;

import dev.doctor4t.wathe.game.GameConstants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentProvider;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家 CCA 组件的“额外观察者同步”公开 API。
 *
 * <p>Wathe 的金币、心情、体力、便签等玩家私有状态默认只同步给本人，避免一个玩家的
 * 连续状态变化被广播给附近所有客户端。但扩展职业有时确实需要在客户端观察其他玩家的
 * 某项状态，例如医师根据目标的中毒状态绘制本能透视。此时扩展应在这里注册窄规则，
 * 不要把整个组件重新改回全员同步。</p>
 *
 * <p>规则只能“额外允许”收件人，组件所属玩家本人始终允许。普通状态变化仍由组件自己的
 * CCA sync 发送；本 API 每秒只检查观察权限变化，在新增权限时补发、失去权限时清除客户端
 * 缓存，权限未变化时不发包。扩展也可调用 {@link #syncTrackedToRecipient} 立即刷新。</p>
 *
 * <p>当前支持 Wathe 的 poison/mood/stamina/shop/note/grenade_throw_mode/instinct 七个私有组件。
 * 它们均使用 CCA 默认 NBT 同步格式，空 NBT 会将客户端组件恢复默认值。其它组件若使用自定义
 * 二进制包，不能直接注册到这里，否则撤销权限时的清空包不兼容。停电药水归属组件仍仅在服务端。</p>
 */
public final class PlayerComponentSyncApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<RuleEntry> RULE_COMPARATOR =
            Comparator.comparingInt(RuleEntry::priority).reversed()
                    .thenComparing(Comparator.comparingLong(RuleEntry::order).reversed());
    private static final List<RuleEntry> RULES = new ArrayList<>();
    private static final Set<String> SUPPORTED_COMPONENTS = Set.of(
            "wathe:poison", "wathe:mood", "wathe:stamina", "wathe:shop",
            "wathe:note", "wathe:grenade_throw_mode", "wathe:instinct");

    /*
     * 仅记录网络观察关系，使用实体实例而不是 UUID 区分断线重连/重生后的新实体。
     * 此缓存不是玩法状态、不写 NBT，也不加入时停快照：回溯改变职业后应重新按当前真实身份
     * 判定权限，而不是把旧网络连接关系当作历史玩法状态恢复。
     */
    private static final Map<ComponentKey<? extends AutoSyncedComponent>,
            Map<ServerPlayerEntity, Set<ServerPlayerEntity>>> OBSERVERS = new LinkedHashMap<>();
    private static boolean initialized = false;
    private static long nextOrder = 0L;

    private PlayerComponentSyncApi() {
    }

    /** 注册网络缓存生命周期与低频权限检查；不注册任何默认的额外观察权限。 */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % GameConstants.COMPONENT_OBSERVER_REFRESH_INTERVAL_TICKS == 0) {
                refreshObservers(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> OBSERVERS.values().forEach(Map::clear));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            for (var owners : OBSERVERS.values()) {
                owners.remove(handler.player);
                owners.values().forEach(recipients -> recipients.remove(handler.player));
            }
        });
    }

    /**
     * 注册某个玩家组件的额外观察者规则。
     *
     * <p>同一个 registrationId 重复注册会替换旧规则，方便扩展初始化和兼容层安全重入。
     * handler 返回 true 只代表“允许该 recipient 接收 owner 的这个组件”，不改变服务端玩法。
     * 多个规则按 priority 从高到低询问，false 表示继续；任何规则允许即放行，不提供否决本人
     * 或其它模组授权的语义。规则只在服务端计算，不要读取 MinecraftClient 或客户端本能按键。</p>
     */
    public static synchronized void registerObserverRule(
            @NotNull Identifier registrationId,
            @NotNull ComponentKey<? extends AutoSyncedComponent> key,
            int priority,
            @NotNull ObserverRule handler
    ) {
        Objects.requireNonNull(registrationId, "registrationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handler, "handler");
        if (!SUPPORTED_COMPONENTS.contains(key.getId().toString())) {
            throw new IllegalArgumentException("Unsupported private player component: " + key.getId());
        }
        RULES.removeIf(entry -> entry.registrationId().equals(registrationId));
        RULES.add(new RuleEntry(registrationId, key.getId(), priority, nextOrder++, handler));
        RULES.sort(RULE_COMPARATOR);
        OBSERVERS.computeIfAbsent(key, ignored -> new HashMap<>());
    }

    /**
     * 解析目标组件是否应同步给指定客户端。
     */
    public static boolean shouldSyncWith(
            @NotNull Identifier componentId,
            @NotNull PlayerEntity owner,
            @NotNull ServerPlayerEntity recipient
    ) {
        if (owner.equals(recipient)) {
            return true;
        }
        if (owner.getWorld() != recipient.getWorld()) {
            return false;
        }

        ObserverContext context = new ObserverContext(componentId, owner, recipient);
        for (RuleEntry entry : ruleSnapshot()) {
            if (entry.componentId().equals(componentId) && entry.handler().shouldSync(context)) {
                /*
                 * CCA 的实体初始同步或组件主动 sync 可能发生在两次周期检查之间。
                 * 授权放行时立即记住这条观察关系，防止“短暂获权并收包、检查前又失权”
                 * 被周期快照漏掉，导致客户端旧数据一直没有收到清空包。
                 * 谓词被单独查询时也允许保守记录：多发一次空包比漏清缓存更安全。
                 */
                if (owner instanceof ServerPlayerEntity serverOwner) {
                    for (var binding : OBSERVERS.entrySet()) {
                        if (binding.getKey().getId().equals(componentId)) {
                            binding.getValue().computeIfAbsent(serverOwner, ignored -> new HashSet<>())
                                    .add(recipient);
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 给刚获得观察能力的玩家补发当前正在追踪的目标组件。
     *
     * <p>常规状态变化和实体开始被追踪时，CCA 会按 shouldSyncWith 自动发送；这个入口只用于
     * 局中转职等“观察者权限变化，但目标组件本身没有发生变化”的边界。仍然只遍历客户端
     * 已在追踪的玩家，不会把全服离线/远距离玩家状态无条件塞给该客户端。</p>
     */
    public static <C extends AutoSyncedComponent> void syncTrackedToRecipient(
            @NotNull ComponentKey<C> key,
            @NotNull ServerPlayerEntity recipient
    ) {
        var owners = OBSERVERS.get(key);
        if (owners == null) return;
        for (ServerPlayerEntity owner : recipient.getServerWorld().getPlayers()) {
            // 本入口只补发“观察别人的组件”；所属玩家自己的组件继续由原 CCA 生命周期同步。
            if (owner.equals(recipient)) continue;
            refreshRecipient(key, owner, recipient,
                    PlayerLookup.tracking(owner).contains(recipient),
                    owners.computeIfAbsent(owner, ignored -> new HashSet<>()), true);
        }
    }

    /**
     * 服务端可在完成职业/词条状态变更后主动调用；周期检查也调用这里。
     * 只对已注册组件检查实体追踪关系，未新增/撤销授权时不重复发送组件包。
     */
    public static void refreshObservers(@NotNull MinecraftServer server) {
        Set<ServerPlayerEntity> online = new HashSet<>(server.getPlayerManager().getPlayerList());
        for (var binding : OBSERVERS.entrySet()) {
            var key = binding.getKey();
            var owners = binding.getValue();
            owners.keySet().retainAll(online);
            for (ServerPlayerEntity owner : online) {
                Set<ServerPlayerEntity> previous = owners.computeIfAbsent(owner, ignored -> new HashSet<>());
                Set<ServerPlayerEntity> tracking = new HashSet<>(PlayerLookup.tracking(owner));
                Set<ServerPlayerEntity> candidates = new HashSet<>(tracking);
                candidates.addAll(previous);
                for (ServerPlayerEntity recipient : candidates) {
                    refreshRecipient(key, owner, recipient,
                            online.contains(recipient) && tracking.contains(recipient), previous, false);
                }
            }
        }
    }

    private static void refreshRecipient(ComponentKey<? extends AutoSyncedComponent> key,
                                         ServerPlayerEntity owner, ServerPlayerEntity recipient,
                                         boolean tracking, Set<ServerPlayerEntity> previous, boolean force) {
        if (owner.equals(recipient)) return;
        if (!tracking) {
            // 客户端已不追踪目标时不会再保留该实体组件，禁止发送无法定位目标的包。
            previous.remove(recipient);
            return;
        }
        // shouldSyncWith 自身会登记允许关系，必须在调用前读取旧值，才能识别首次授权并补发。
        boolean previouslyAllowed = previous.contains(recipient);
        if (key.get(owner).shouldSyncWith(recipient)) {
            previous.add(recipient);
            if (!previouslyAllowed || force) {
                key.syncWith(recipient, (ComponentProvider) owner);
            }
        } else if (previous.remove(recipient)) {
            /*
             * 死亡、换职或回溯失去观察能力后，必须清掉该客户端已拿到的旧状态。
             * 此处只对曾经真实放行过的观察者清空，避免普通职业每次分配时都收到无意义空包。
             * shouldSyncWith 会在真实数据获准发送时登记关系，因此短暂获权也不会漏掉撤权清理。
             * 接收者必须仍在追踪目标，允许谓词只用于这个清空包；
             * 绝不能绕过 shouldSyncWith 发送真实数据，也不能调用 owner.reset 改服务端玩法。
             */
            key.syncWith(recipient, (ComponentProvider) owner,
                    (buf, ignored) -> buf.writeNbt(new NbtCompound()), ignored -> true);
        }
    }

    private static synchronized @NotNull List<RuleEntry> ruleSnapshot() {
        return List.copyOf(RULES);
    }

    public record ObserverContext(
            @NotNull Identifier componentId,
            @NotNull PlayerEntity owner,
            @NotNull ServerPlayerEntity recipient
    ) {
    }

    @FunctionalInterface
    public interface ObserverRule {
        boolean shouldSync(@NotNull ObserverContext context);
    }

    private record RuleEntry(
            @NotNull Identifier registrationId,
            @NotNull Identifier componentId,
            int priority,
            long order,
            @NotNull ObserverRule handler
    ) {
    }
}
