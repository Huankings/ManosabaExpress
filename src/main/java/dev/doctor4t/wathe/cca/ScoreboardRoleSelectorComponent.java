package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.WatheRoles;
import dev.doctor4t.wathe.client.gui.RoleAnnouncementTexts;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;

import java.util.*;

public class ScoreboardRoleSelectorComponent implements AutoSyncedComponent {
    public static final ComponentKey<ScoreboardRoleSelectorComponent> KEY = ComponentRegistry.getOrCreate(Wathe.id("rolecounter"), ScoreboardRoleSelectorComponent.class);
    public final Scoreboard scoreboard;
    public final MinecraftServer server;
    public final Map<UUID, Integer> killerRounds = new HashMap<>();
    public final Map<UUID, Integer> vigilanteRounds = new HashMap<>();
    public final List<UUID> forcedKillers = new ArrayList<>();
    public final List<UUID> forcedVigilantes = new ArrayList<>();

    public ScoreboardRoleSelectorComponent(Scoreboard scoreboard, @Nullable MinecraftServer server) {
        this.scoreboard = scoreboard;
        this.server = server;
    }

    public int reset() {
        this.killerRounds.clear();
        this.vigilanteRounds.clear();
        return 1;
    }

    public void checkWeights(@NotNull ServerCommandSource source) {
        double killerTotal = 0d;
        double vigilanteTotal = 0d;
        for (ServerPlayerEntity player : source.getWorld().getPlayers()) {
            killerTotal += Math.exp(-this.killerRounds.getOrDefault(player.getUuid(), 0) * 4);
            vigilanteTotal += Math.exp(-this.vigilanteRounds.getOrDefault(player.getUuid(), 0) * 4);
        }
        MutableText text = Text.literal("Role Weights:").formatted(Formatting.GRAY);
        for (ServerPlayerEntity player : source.getWorld().getPlayers()) {
            text = text.append("\n").append(player.getDisplayName());
            Integer killerRounds = this.killerRounds.getOrDefault(player.getUuid(), 0);
            double killerWeight = Math.exp(-killerRounds * 4);
            double killerPercent = killerWeight / killerTotal * 100;
            Integer vigilanteRounds = this.vigilanteRounds.getOrDefault(player.getUuid(), 0);
            double vigilanteWeight = Math.exp(-vigilanteRounds * 4);
            double vigilantePercent = vigilanteWeight / vigilanteTotal * 100;
            text.append(
                    Text.literal("\n  Killer (").withColor(RoleAnnouncementTexts.KILLER.colour)
                            .append(Text.literal("%d".formatted(killerRounds)).withColor(0x808080))
                            .append(Text.literal("): ").withColor(RoleAnnouncementTexts.KILLER.colour))
                            .append(Text.literal("%.2f%%".formatted(killerPercent)).withColor(0x808080))
            );
            text.append(
                    Text.literal("\n  Vigilante (").withColor(RoleAnnouncementTexts.VIGILANTE.colour)
                            .append(Text.literal("%d".formatted(vigilanteRounds)).withColor(0x808080))
                            .append(Text.literal("): ").withColor(RoleAnnouncementTexts.VIGILANTE.colour))
                            .append(Text.literal("%.2f%%".formatted(vigilantePercent)).withColor(0x808080))
            );
        }
        MutableText finalText = text;
        source.sendFeedback(() -> finalText, false);
    }

    public void setKillerRounds(@NotNull ServerCommandSource source, @NotNull ServerPlayerEntity player, int times) {
        if (times < 0) times = 0;
        if (times == 0) this.killerRounds.remove(player.getUuid());
        else this.killerRounds.put(player.getUuid(), times);
        int finalTimes = times;
        source.sendFeedback(() -> Text.literal("Set ").formatted(Formatting.GRAY)
                .append(player.getDisplayName().copy().formatted(Formatting.YELLOW))
                .append(Text.literal("'s Killer rounds to ").formatted(Formatting.GRAY))
                .append(Text.literal("%d".formatted(finalTimes)).withColor(0x808080))
                .append(Text.literal(".").formatted(Formatting.GRAY)), false);
    }

    public void setVigilanteRounds(@NotNull ServerCommandSource source, @NotNull ServerPlayerEntity player, int times) {
        if (times < 0) times = 0;
        if (times == 0) this.vigilanteRounds.remove(player.getUuid());
        else this.vigilanteRounds.put(player.getUuid(), times);
        int finalTimes = times;
        source.sendFeedback(() -> Text.literal("Set ").formatted(Formatting.GRAY)
                .append(player.getDisplayName().copy().formatted(Formatting.YELLOW))
                .append(Text.literal("'s Vigilante rounds to ").formatted(Formatting.GRAY))
                .append(Text.literal("%d".formatted(finalTimes)).withColor(0x808080))
                .append(Text.literal(".").formatted(Formatting.GRAY)), false);
    }

    public int assignKillers(ServerWorld world, GameWorldComponent gameComponent, @NotNull List<ServerPlayerEntity> players, int killerCount) {
        this.reduceKillers();
        ArrayList<UUID> killers = new ArrayList<>();
        for (UUID uuid : this.forcedKillers) {
            killers.add(uuid);
            killerCount--;
            this.killerRounds.put(uuid, this.killerRounds.getOrDefault(uuid, 1) + 1);
        }
        this.forcedKillers.clear();
        HashMap<ServerPlayerEntity, Float> map = new HashMap<>();
        float total = 0f;
        for (ServerPlayerEntity player : players) {
            float weight = (float) Math.exp(-this.killerRounds.getOrDefault(player.getUuid(), 0) * 4);
            if (!GameWorldComponent.KEY.get(world).areWeightsEnabled()) weight = 1;
            map.put(player, weight);
            total += weight;
        }
        for (int i = 0; i < killerCount; i++) {
            float random = world.getRandom().nextFloat() * total;
            for (Map.Entry<ServerPlayerEntity, Float> entry : map.entrySet()) {
                random -= entry.getValue();
                if (random <= 0) {
                    killers.add(entry.getKey().getUuid());
                    total -= entry.getValue();
                    map.remove(entry.getKey());
                    this.killerRounds.put(entry.getKey().getUuid(), this.killerRounds.getOrDefault(entry.getKey().getUuid(), 1) + 1);
                    break;
                }
            }
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

    private void reduceKillers() {
        int minimum = Integer.MAX_VALUE;
        for (Integer times : this.killerRounds.values()) minimum = Math.min(minimum, times);
        for (UUID times : this.killerRounds.keySet())
            this.killerRounds.put(times, this.killerRounds.get(times) - minimum);
    }

    public void assignVigilantes(ServerWorld world, GameWorldComponent gameComponent, @NotNull List<ServerPlayerEntity> players, int vigilanteCount) {
        this.reduceVigilantes();
        ArrayList<ServerPlayerEntity> vigilantes = new ArrayList<>();
        for (UUID uuid : this.forcedVigilantes) {
            PlayerEntity player = world.getPlayerByUuid(uuid);
            if (player instanceof ServerPlayerEntity serverPlayer && players.contains(serverPlayer) && !gameComponent.canUseKillerFeatures(player)) {
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
                this.vigilanteRounds.put(player.getUuid(), this.vigilanteRounds.getOrDefault(player.getUuid(), 1) + 1);
            }
        }
        this.forcedVigilantes.clear();
        HashMap<ServerPlayerEntity, Float> map = new HashMap<>();
        float total = 0f;
        for (ServerPlayerEntity player : players) {
            if (gameComponent.isRole(player, WatheRoles.KILLER)) continue;
            float weight = (float) Math.exp(-this.vigilanteRounds.getOrDefault(player.getUuid(), 0) * 4);
            if (!GameWorldComponent.KEY.get(world).areWeightsEnabled()) weight = 1;
            map.put(player, weight);
            total += weight;
        }
        for (int i = 0; i < vigilanteCount; i++) {
            float random = world.getRandom().nextFloat() * total;
            for (Map.Entry<ServerPlayerEntity, Float> entry : map.entrySet()) {
                random -= entry.getValue();
                if (random <= 0) {
                    vigilantes.add(entry.getKey());
                    total -= entry.getValue();
                    map.remove(entry.getKey());
                    this.vigilanteRounds.put(entry.getKey().getUuid(), this.vigilanteRounds.getOrDefault(entry.getKey().getUuid(), 1) + 1);
                    break;
                }
            }
        }
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

    private void reduceVigilantes() {
        int minimum = Integer.MAX_VALUE;
        for (Integer times : this.vigilanteRounds.values()) minimum = Math.min(minimum, times);
        for (UUID times : this.vigilanteRounds.keySet())
            this.vigilanteRounds.put(times, this.vigilanteRounds.get(times) - minimum);
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
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
    }

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.killerRounds.clear();
        for (NbtElement element : tag.getList("killerRounds", 10)) {
            NbtCompound compound = (NbtCompound) element;
            if (!compound.contains("uuid") || !compound.contains("times")) continue;
            this.killerRounds.put(compound.getUuid("uuid"), compound.getInt("times"));
        }
        this.vigilanteRounds.clear();
        for (NbtElement element : tag.getList("vigilanteRounds", 10)) {
            NbtCompound compound = (NbtCompound) element;
            if (!compound.contains("uuid") || !compound.contains("times")) continue;
            this.vigilanteRounds.put(compound.getUuid("uuid"), compound.getInt("times"));
        }
    }
}
