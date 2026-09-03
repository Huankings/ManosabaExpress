package dev.doctor4t.wathe.api.tray;

import dev.doctor4t.wathe.api.event.CanSeePoison;
import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.index.WatheParticles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 托盘粒子公开注册表。
 *
 * <p>默认 provider 保留 Wathe 毒药粒子的可见性规则；扩展只需注册自己的 provider，
 * 不再注入 BeveragePlateBlockEntity 的 clientTick。provider 返回 true 表示本次已经处理，
 * 返回 false 则继续询问低优先级 provider。</p>
 */
public final class TrayParticleRegistry {
    @FunctionalInterface
    public interface Provider {
        boolean spawn(TrayParticleContext context);
    }

    private record Entry(String id, int priority, Provider provider) {
    }

    private static final List<Entry> PROVIDERS = new ArrayList<>();

    private TrayParticleRegistry() {
    }

    public static void registerProvider(String id, int priority, Provider provider) {
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new Entry(id, priority, provider));
        PROVIDERS.sort(Comparator.comparingInt(Entry::priority).reversed());
    }

    public static void tickClient(ClientWorld world, BlockPos pos, BeveragePlateBlockEntity plate) {
        ClientPlayerEntity viewer = MinecraftClient.getInstance().player;
        if (viewer == null) {
            return;
        }
        TrayParticleContext context = new TrayParticleContext(world, pos, plate, viewer);
        for (Entry entry : PROVIDERS) {
            if (entry.provider().spawn(context)) {
                break;
            }
        }
    }

    public static void registerDefaultProvider() {
        registerProvider("wathe:poison", Integer.MIN_VALUE, context -> {
            BeveragePlateBlockEntity plate = context.plate();
            if (plate.getPoisoner() == null || (!WatheClient.isKiller() && !CanSeePoison.EVENT.invoker().visible(context.viewer()))) {
                return false;
            }
            if (context.world().getRandom().nextBetween(0, 20) < 17) {
                return true;
            }
            context.world().addParticle(
                    WatheParticles.POISON,
                    context.pos().getX() + 0.5f,
                    context.pos().getY(),
                    context.pos().getZ() + 0.5f,
                    0f, 0.05f, 0f
            );
            return true;
        });
    }
}
