package dev.doctor4t.wathe.api.bed;

import dev.doctor4t.wathe.api.event.CanSeePoison;
import dev.doctor4t.wathe.block_entity.TrimmedBedBlockEntity;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.index.WatheParticles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 床附加效果的客户端粒子公开注册表。
 *
 * <p>扩展职业应通过 provider 注册自己的床粒子，不要再注入
 * {@code TrimmedBedBlockEntity.clientTick}。注册表只负责客户端表现，
 * 不会改变床效果的服务端触发、所有者、消耗和回放逻辑。</p>
 *
 * <p>provider 按优先级从高到低执行：返回 {@code true} 表示本次已经处理，
 * 返回 {@code false} 才会继续询问更低优先级 provider。这样扩展可以在不复制
 * Wathe 床实体 tick 的情况下覆盖或补充指定床效果的视觉表现。</p>
 */
public final class BedParticleRegistry {
    @FunctionalInterface
    public interface Provider {
        boolean spawn(BedParticleContext context);
    }

    private record Entry(String id, int priority, Provider provider) {
    }

    private static final List<Entry> PROVIDERS = new ArrayList<>();

    private BedParticleRegistry() {
    }

    /** 注册或替换一个床粒子 provider。 */
    public static void registerProvider(String id, int priority, Provider provider) {
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new Entry(id, priority, provider));
        PROVIDERS.sort(Comparator.comparingInt(Entry::priority).reversed());
    }

    /**
     * 由床方块实体的客户端 tick 调用。
     *
     * <p>观察者为空时直接跳过，避免客户端尚未完成加入世界时访问玩家状态。
     * provider 自己仍然需要对职业、阵营和可见性做最终判断。</p>
     */
    public static void tickClient(ClientWorld world, BlockPos pos, TrimmedBedBlockEntity bed) {
        ClientPlayerEntity viewer = MinecraftClient.getInstance().player;
        if (viewer == null) {
            return;
        }

        BedParticleContext context = new BedParticleContext(world, pos, bed, viewer);
        for (Entry entry : PROVIDERS) {
            if (entry.provider().spawn(context)) {
                break;
            }
        }
    }

    /**
     * 注册 Wathe 原生蝎子床粒子。
     *
     * <p>可见性和刷新概率沿用迁移前的 Mixin 逻辑，确保公开化不会改变原有玩法信息量。
     * 该 provider 使用最低优先级，扩展可以在确有需要时为同一效果注册更高优先级表现。</p>
     */
    public static void registerDefaultProvider() {
        registerProvider("wathe:scorpion", Integer.MIN_VALUE, context -> {
            String scorpionId = Registries.ITEM.getId(WatheItems.SCORPION).toString();
            if (!scorpionId.equals(context.bed().getBedEffect())) {
                return false;
            }

            if (!WatheClient.isKiller()
                    && !CanSeePoison.EVENT.invoker().visible(context.viewer())) {
                // 当前 provider 已识别该效果，但观察者没有权限看到它；不生成粒子。
                return true;
            }

            if (context.world().getRandom().nextBetween(0, 20) < 17) {
                return true;
            }

            context.world().addParticle(
                    WatheParticles.POISON,
                    context.pos().getX() + 0.5f,
                    context.pos().getY() + 0.5f,
                    context.pos().getZ() + 0.5f,
                    0f,
                    0.05f,
                    0f
            );
            return true;
        });
    }
}
