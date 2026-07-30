package dev.doctor4t.wathe.api.psycho;

import dev.doctor4t.wathe.cca.PlayerPsychoComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一次疯魔护盾判定的只读上下文。
 *
 * <p>扩展职业通常只需要判断 {@code deathReason} 或额外回放数据，
 * 例如狙击枪这类“明确应该穿透疯魔护盾”的伤害返回 {@link PsychoShieldResult#BYPASS}。</p>
 */
public record PsychoShieldContext(
        @NotNull PlayerEntity victim,
        @Nullable PlayerEntity attacker,
        @NotNull Identifier deathReason,
        @NotNull PlayerPsychoComponent component,
        @NotNull PsychoModeProfile profile,
        @NotNull NbtCompound damageReplayData
) {
}
