package dev.doctor4t.wathe.cca;

import com.mojang.authlib.GameProfile;
import dev.doctor4t.wathe.api.Faction;
import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.client.gui.RoleAnnouncementTexts;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class GameRoundEndComponent implements AutoSyncedComponent {
    public static final ComponentKey<GameRoundEndComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("roundend"), GameRoundEndComponent.class);
    private final World world;
    private final List<RoundEndData> players = new ArrayList<>();
    /**
     * 结算页额外显示的“真实职业”信息。
     *
     * 这里故意不把这些字段塞进 {@link RoundEndData} 的构造参数里，
     * 因为 StupidExpress / KinsWathe 都对原本的三参构造做了 mixin 注入。
     * 通过旁路同步一份显示数据，可以在不破坏现有兼容性的前提下，
     * 给结算页补充“职业名 + 职业颜色”。
     */
    private final HashMap<UUID, RoundEndRoleDisplay> roleDisplays = new HashMap<>();
    private GameFunctions.WinStatus winStatus = GameFunctions.WinStatus.NONE;

    public GameRoundEndComponent(World world) {
        this.world = world;
    }

    public void sync() {
        KEY.sync(this.world);
    }

    public void setRoundEndData(@NotNull List<ServerPlayerEntity> players, GameFunctions.WinStatus winStatus) {
        this.players.clear();
        this.roleDisplays.clear();
        GameWorldComponent game = GameWorldComponent.KEY.get(this.world);
        for (ServerPlayerEntity player : players) {
            Role roleData = game.getRole(player);
            /*
             * 这里专门跳过“对局中途才加入，但本局并没有参与身份分配”的玩家。
             *
             * 原因：
             * 1. 原版结束结算时直接把当前世界在线玩家都传进来；
             * 2. 中途加入者通常没有被分配到任何职业，roleData 会是 null；
             * 3. 这种玩家如果继续写进结算列表，就会得到一个 BLANK 阵营；
             * 4. 而结算页布局只会给平民 / 义警 / 杀手 / 扩展中立等有效阵营分配坐标，
             *    最终就会出现头像被画到奇怪位置的问题。
             *
             * 因此这里直接不把“无职业玩家”写入结算数据。
             * 这样既不会误把他们当平民，也能保证主模组和扩展模组的结算布局稳定。
             */
            if (roleData == null) {
                continue;
            }
            RoleAnnouncementTexts.RoleAnnouncementText role = getAnnouncementByFaction(roleData == null ? null : roleData.getFaction());
            this.players.add(new RoundEndData(player.getGameProfile(), role, !GameFunctions.isPlayerAliveAndSurvival(player)));
            this.roleDisplays.put(player.getUuid(), RoundEndRoleDisplay.fromRole(roleData, role));
        }
        this.winStatus = winStatus;
        this.sync();
    }

    /**
     * 把阵营统一映射到结算页左侧的大类标签。
     *
     * <p>这样扩展职业只要正确注册了 {@link Faction}，
     * 结算页就会自动把它们归进对应的“平民 / 义警 / 杀手 / 中立”栏目，
     * 不再依赖“是不是恰好等于原版 vigilante 对象”。</p>
     */
    public static @NotNull RoleAnnouncementTexts.RoleAnnouncementText getAnnouncementByFaction(@Nullable Faction faction) {
        if (faction == null) {
            return RoleAnnouncementTexts.BLANK;
        }
        return switch (faction) {
            case KILLER -> RoleAnnouncementTexts.KILLER;
            case VIGILANTE -> RoleAnnouncementTexts.VIGILANTE;
            case NEUTRAL -> RoleAnnouncementTexts.LOOSE_END;
            case CIVILIAN -> RoleAnnouncementTexts.CIVILIAN;
        };
    }

    public boolean didWin(UUID uuid) {
        if (GameFunctions.WinStatus.NONE == this.winStatus) return false;
        for (RoundEndData detail : this.players) {
            if (!detail.player.getId().equals(uuid)) continue;
            return switch (this.winStatus) {
                case KILLERS -> detail.role == RoleAnnouncementTexts.KILLER;
                case PASSENGERS, TIME -> detail.role != RoleAnnouncementTexts.KILLER;
                default -> false;
            };
        }
        return false;
    }

    public List<RoundEndData> getPlayers() {
        return this.players;
    }

    public @NotNull RoundEndRoleDisplay getRoleDisplay(UUID uuid) {
        return this.roleDisplays.getOrDefault(uuid, RoundEndRoleDisplay.BLANK);
    }

    public GameFunctions.WinStatus getWinStatus() {
        return this.winStatus;
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        for (RoundEndData detail : this.players) {
            NbtCompound playerTag = detail.writeToNbt();
            this.getRoleDisplay(detail.player().getId()).writeToNbt(playerTag);
            list.add(playerTag);
        }
        tag.put("players", list);
        tag.putInt("winstatus", this.winStatus.ordinal());
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.players.clear();
        this.roleDisplays.clear();
        for (NbtElement element : tag.getList("players", 10)) {
            NbtCompound playerTag = (NbtCompound) element;
            RoundEndData detail = new RoundEndData(playerTag);
            this.players.add(detail);
            this.roleDisplays.put(detail.player().getId(), RoundEndRoleDisplay.fromNbt(playerTag, detail.role()));
        }
        this.winStatus = GameFunctions.WinStatus.values()[tag.getInt("winstatus")];
    }

    public record RoundEndData(GameProfile player, RoleAnnouncementTexts.RoleAnnouncementText role, boolean wasDead) {
        public RoundEndData(@NotNull NbtCompound tag) {
            this(new GameProfile(tag.getUuid("uuid"), tag.getString("name")), RoleAnnouncementTexts.ROLE_ANNOUNCEMENT_TEXTS.get(tag.getInt("role")), tag.getBoolean("wasDead"));
        }

        public @NotNull NbtCompound writeToNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("uuid", this.player.getId());
            tag.putString("name", this.player.getName());
            tag.putInt("role", RoleAnnouncementTexts.ROLE_ANNOUNCEMENT_TEXTS.indexOf(this.role));
            tag.putBoolean("wasDead", this.wasDead);
            return tag;
        }
    }

    public record RoundEndRoleDisplay(String translationKey, String fallbackName, int color) {
        public static final RoundEndRoleDisplay BLANK = new RoundEndRoleDisplay("", "", 0xFFFFFF);

        public static @NotNull RoundEndRoleDisplay fromRole(@Nullable Role role, @NotNull RoleAnnouncementTexts.RoleAnnouncementText teamRole) {
            if (role == null) {
                return fromTeamRole(teamRole);
            }

            Identifier identifier = role.identifier();
            String translationKey = identifier.getNamespace().equals(Wathe.MOD_ID)
                    ? "announcement.title." + identifier.getPath()
                    : "announcement.role." + identifier.getNamespace() + "." + identifier.getPath();
            return new RoundEndRoleDisplay(translationKey, prettifyIdentifierPath(identifier.getPath()), role.color());
        }

        public static @NotNull RoundEndRoleDisplay fromNbt(@NotNull NbtCompound tag, @NotNull RoleAnnouncementTexts.RoleAnnouncementText teamRole) {
            if (tag.contains("displayRoleTranslationKey")) {
                return new RoundEndRoleDisplay(
                        tag.getString("displayRoleTranslationKey"),
                        tag.getString("displayRoleFallback"),
                        tag.getInt("displayRoleColor")
                );
            }
            return fromTeamRole(teamRole);
        }

        public void writeToNbt(@NotNull NbtCompound tag) {
            tag.putString("displayRoleTranslationKey", this.translationKey);
            tag.putString("displayRoleFallback", this.fallbackName);
            tag.putInt("displayRoleColor", this.color);
        }

        private static @NotNull RoundEndRoleDisplay fromTeamRole(@NotNull RoleAnnouncementTexts.RoleAnnouncementText teamRole) {
            if (teamRole == RoleAnnouncementTexts.KILLER) {
                return new RoundEndRoleDisplay("announcement.title.killer", "Killer", teamRole.colour);
            }
            if (teamRole == RoleAnnouncementTexts.VIGILANTE) {
                return new RoundEndRoleDisplay("announcement.title.vigilante", "Vigilante", teamRole.colour);
            }
            if (teamRole == RoleAnnouncementTexts.LOOSE_END) {
                return new RoundEndRoleDisplay("announcement.title.loose_end", "Loose End", teamRole.colour);
            }
            return new RoundEndRoleDisplay("announcement.title.civilian", "Civilian", teamRole.colour);
        }

        private static @NotNull String prettifyIdentifierPath(@NotNull String path) {
            String[] parts = path.split("_");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) {
                    builder.append(part.substring(1));
                }
            }
            return builder.isEmpty() ? path : builder.toString();
        }
    }
}
