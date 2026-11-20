package dev.doctor4t.wathe.api;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** 临时控制其他身体时，区分行动权限、胜负人数、任务驱动者和任务受益者。 */
public final class PlayerParticipationApi {
    private static final CopyOnWriteArrayList<Rule> RULES = new CopyOnWriteArrayList<>();

    private PlayerParticipationApi() {}

    public static void register(Rule rule) { RULES.add(rule); }

    public static Role effectiveRole(World world, UUID player, Role assigned) {
        Role role = assigned;
        for (Rule rule : RULES) role = rule.effectiveRole(world, player, role);
        return role;
    }

    public static boolean countsForVictory(PlayerEntity player) {
        return RULES.stream().allMatch(rule -> rule.countsForVictory(player));
    }

    public static boolean canUseShop(PlayerEntity player) {
        return RULES.stream().allMatch(rule -> rule.canUseShop(player));
    }

    public static boolean canChangeCamera(ServerPlayerEntity player, Entity target) {
        return RULES.stream().allMatch(rule -> rule.canChangeCamera(player, target));
    }

    public static boolean ticksMood(PlayerEntity player) {
        return RULES.stream().allMatch(rule -> rule.ticksMood(player));
    }

    public static ServerPlayerEntity taskBeneficiary(ServerPlayerEntity player) {
        for (Rule rule : RULES) {
            ServerPlayerEntity owner = rule.taskBeneficiary(player);
            if (owner != player) return owner;
        }
        return player;
    }

    public interface Rule {
        default Role effectiveRole(World world, UUID player, Role assigned) { return assigned; }
        default boolean countsForVictory(PlayerEntity player) { return true; }
        default boolean canUseShop(PlayerEntity player) { return true; }
        default boolean ticksMood(PlayerEntity player) { return true; }
        default boolean canChangeCamera(ServerPlayerEntity player, Entity target) { return true; }
        default ServerPlayerEntity taskBeneficiary(ServerPlayerEntity player) { return player; }
    }
}
