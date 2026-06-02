package dev.doctor4t.wathe.mixin.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin extends PlayerEntity {
    public AbstractClientPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile gameProfile) {
        super(world, pos, yaw, gameProfile);
    }

    /*
     * 这里保留这个 mixin 只是为了兼容旧版结构，真正的 FOV 修正已经下放到
     * GameRenderer.updateFovMultiplier() 的调用点上。
     *
     * 原因是 1.21.1 下这层目标虽然能编译通过，但在实际运行链路里没有稳定触发，
     * 所以继续把效果挂在这里会出现“只看见 pulse 日志、却看不到 client fov 日志”的情况。
     */
}
