package dev.doctor4t.wathe.api;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.game.GameConstants;
import net.minecraft.util.Identifier;

import java.util.ArrayList;

public class WatheRoles {
    public static final ArrayList<Role> ROLES = new ArrayList<>();

    public static final Role DISCOVERY_CIVILIAN = registerRole(new Role(Wathe.id("discovery_civilian"), 0x36E51B, true, false, Role.MoodType.NONE, -1, true), Faction.CIVILIAN);
    public static final Role CIVILIAN = registerRole(new Role(Wathe.id("civilian"), 0x36E51B, true, false, Role.MoodType.REAL, GameConstants.getInTicks(0, 18), false), Faction.CIVILIAN);
    public static final Role VIGILANTE = registerRole(new Role(Wathe.id("vigilante"), 0x1B8AE5, true, false, Role.MoodType.REAL, GameConstants.getInTicks(0, 22), false), Faction.VIGILANTE);
    public static final Role KILLER = registerRole(new Role(Wathe.id("killer"), 0xC13838, false, true, Role.MoodType.FAKE, -1, true), Faction.KILLER);
    public static final Role LOOSE_END = registerRole(new Role(Wathe.id("loose_end"), 0x9F0000, false, false, Role.MoodType.NONE, -1, false), Faction.NEUTRAL);

    public static Role registerRole(Role role) {
        ROLES.add(role);
        return role;
    }

    /**
     * 为角色显式声明回放用阵营。
     *
     * <p>扩展职业模组如果存在“中立转杀手”“职业色和阵营色分离”等情况，
     * 推荐在注册角色后补这一层，而不是去改动 {@link Role} 的构造参数。</p>
     */
    public static Role registerRole(Role role, Faction faction) {
        role.setFactionOverride(faction);
        return registerRole(role);
    }

    /**
     * 便捷注册“平民阵营”职业。
     *
     * <p>扩展模组后续如果只想明确声明阵营，不想每次都手写
     * {@code registerRole(role, Faction.CIVILIAN)}，可以直接走这些便捷入口。</p>
     */
    public static Role registerCivilianRole(Role role) {
        return registerRole(role, Faction.CIVILIAN);
    }

    /**
     * 便捷注册“义警阵营”职业。
     */
    public static Role registerVigilanteRole(Role role) {
        return registerRole(role, Faction.VIGILANTE);
    }

    /**
     * 便捷注册“杀手阵营”职业。
     */
    public static Role registerKillerRole(Role role) {
        return registerRole(role, Faction.KILLER);
    }

    /**
     * 便捷注册“中立阵营”职业。
     */
    public static Role registerNeutralRole(Role role) {
        return registerRole(role, Faction.NEUTRAL);
    }

    public static void setRoleFaction(Role role, Faction faction) {
        if (role != null) {
            role.setFactionOverride(faction);
        }
    }

    public static Role getRole(Identifier identifier) {
        if (identifier == null) {
            return null;
        }
        for (Role role : ROLES) {
            if (role.identifier().equals(identifier)) {
                return role;
            }
        }
        return null;
    }
}
