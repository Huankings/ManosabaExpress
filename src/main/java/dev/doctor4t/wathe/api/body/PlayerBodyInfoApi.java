package dev.doctor4t.wathe.api.body;

import com.mojang.authlib.GameProfile;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.api.death.DeathContext;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerPoisonComponent;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家尸体基础验尸信息的公开服务端 API。
 *
 * <p>Wathe 负责保存死者、死因、死亡时职业、凶手和可回溯死亡时间；Harpy 只补词条，
 * NoellesRoles / kinssaba 的人工尸体只调用 {@link #initializeSynthetic}。这样扩展不再通过
 * 反射或私有 CCA 互相写死因字段。</p>
 */
public final class PlayerBodyInfoApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());
    private static final List<Entry> INITIALIZED_HANDLERS = new ArrayList<>();
    private static long nextOrder;

    private PlayerBodyInfoApi() {
    }

    public static void registerInitializedHandler(@NotNull Identifier id,
                                                  int priority,
                                                  @NotNull InitializedHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (INITIALIZED_HANDLERS) {
            INITIALIZED_HANDLERS.removeIf(entry -> entry.id().equals(id));
            INITIALIZED_HANDLERS.add(new Entry(id, priority, nextOrder++, handler));
            INITIALIZED_HANDLERS.sort(ENTRY_COMPARATOR);
        }
    }

    /**
     * 从 Wathe 已确认成立的死亡流程生成尸体快照。
     */
    public static void initializeFromDeath(@NotNull PlayerBodyEntity body, @NotNull DeathContext context) {
        PlayerEntity victim = context.victim();
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(victim.getWorld());
        Role victimRole = gameWorld.getRole(victim);
        UUID killerUuid = resolveKillerUuid(context);
        String killerName = resolvePlayerName(context, killerUuid);
        Role killerRole = killerUuid == null ? null : gameWorld.getRole(killerUuid);

        initialize(body, new PlayerBodyInfoSnapshot(
                victim.getUuid(),
                victim.getGameProfile().getName(),
                context.deathReason(),
                victimRole == null ? WatheRoles.CIVILIAN.identifier() : victimRole.identifier(),
                killerUuid,
                killerName,
                killerRole == null ? null : killerRole.identifier(),
                false,
                victim.getUuid()
        ));
    }

    /**
     * 初始化扩展主动创建的伪尸体。
     *
     * <p>调用方必须在 spawnEntity 前完成初始化。伪尸体的凶手默认由调用方留空为“未知”；
     * Harpy 会依据 synthetic 和 modifierSourceUuid 生成随机、资格合法的假词条，而不会复制真实词条。</p>
     */
    public static void initializeSynthetic(@NotNull PlayerBodyEntity body,
                                           @NotNull PlayerBodyInfoSnapshot snapshot) {
        if (!snapshot.synthetic()) {
            throw new IllegalArgumentException("Synthetic body snapshot must set synthetic=true");
        }
        initialize(body, snapshot);
    }

    public static void initialize(@NotNull PlayerBodyEntity body,
                                  @NotNull PlayerBodyInfoSnapshot snapshot) {
        body.setPlayerUuid(snapshot.ownerUuid());
        body.setPlayerNameSnapshot(snapshot.ownerName());
        body.setDeathReasonId(snapshot.deathReason().toString());
        body.setPlayerRoleId(snapshot.roleId().toString());
        body.setKillerSnapshot(
                snapshot.killerUuid(),
                snapshot.killerName(),
                snapshot.killerRoleId() == null ? null : snapshot.killerRoleId().toString()
        );
        body.setSynthetic(snapshot.synthetic());
        body.setDeathElapsedTicks(0);

        BodyInfoInitializedContext context = new BodyInfoInitializedContext(body, snapshot);
        for (Entry entry : initializedHandlersSnapshot()) {
            entry.handler().handle(context);
        }
    }

    private static @Nullable UUID resolveKillerUuid(@NotNull DeathContext context) {
        NbtCompound extra = context.extraDeathDataAtStart();
        if (extra != null && extra.containsUuid("replay_actor")) {
            return extra.getUuid("replay_actor");
        }
        if (context.killer() != null) {
            return context.killer().getUuid();
        }
        if (extra != null && extra.containsUuid("poisoner")) {
            return extra.getUuid("poisoner");
        }
        UUID poisoner = PlayerPoisonComponent.KEY.get(context.victim()).getPoisoner();
        return poisoner;
    }

    private static @Nullable String resolvePlayerName(@NotNull DeathContext context, @Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        if (playerUuid.equals(context.victim().getUuid())) {
            return context.victim().getGameProfile().getName();
        }

        NbtCompound extra = context.extraDeathDataAtStart();
        if (extra != null && extra.contains("replay_actor_name")) {
            String replayName = extra.getString("replay_actor_name");
            if (!replayName.isBlank()) {
                return replayName;
            }
        }

        if (context.victim() instanceof ServerPlayerEntity serverVictim) {
            MinecraftServer server = serverVictim.getServer();
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerUuid);
            if (online != null) {
                return online.getGameProfile().getName();
            }
            GameProfile cached = server.getUserCache().getByUuid(playerUuid).orElse(null);
            if (cached != null) {
                return cached.getName();
            }
        }
        return null;
    }

    private static List<Entry> initializedHandlersSnapshot() {
        synchronized (INITIALIZED_HANDLERS) {
            return List.copyOf(INITIALIZED_HANDLERS);
        }
    }

    @FunctionalInterface
    public interface InitializedHandler {
        void handle(@NotNull BodyInfoInitializedContext context);
    }

    private record Entry(@NotNull Identifier id,
                         int priority,
                         long order,
                         @NotNull InitializedHandler handler) {
    }
}
