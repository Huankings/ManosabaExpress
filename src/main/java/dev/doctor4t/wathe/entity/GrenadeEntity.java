package dev.doctor4t.wathe.entity;

import dev.doctor4t.wathe.api.combat.GrenadeApi;
import dev.doctor4t.wathe.api.combat.GrenadeExplosionContext;
import dev.doctor4t.wathe.api.combat.GrenadeExplosionResult;
import dev.doctor4t.wathe.index.WatheEntities;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

public class GrenadeEntity extends ThrownItemEntity {
    public GrenadeEntity(EntityType<?> ignored, World world) {
        super(WatheEntities.GRENADE, world);
    }

    @Override
    protected Item getDefaultItem() {
        return WatheItems.THROWN_GRENADE;
    }

    @Override
    protected void onCollision(HitResult hitResult) {
        /*
         * 父类碰撞只在 Wathe 入口执行一次。扩展 handler 不应再手动调用 super.onCollision，
         * 否则实体状态、命中事件或后续兼容逻辑可能被重复推进。
         */
        super.onCollision(hitResult);
        if (!(this.getWorld() instanceof ServerWorld world)) {
            return;
        }

        GrenadeExplosionContext context = new GrenadeExplosionContext(world, this, hitResult);
        GrenadeExplosionResult result = GrenadeApi.handleExplosion(context);
        if (result == GrenadeExplosionResult.PASS) {
            context.runDefaultExplosion();
        }

        /*
         * PASS、HANDLED 与 CANCEL 最终都结束这枚投掷物。
         * handler 只负责决定爆炸表现/结算，不自行 discard，避免多个扩展处理器争抢实体生命周期。
         */
        this.discard();
    }
}
