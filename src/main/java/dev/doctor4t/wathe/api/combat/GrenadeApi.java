package dev.doctor4t.wathe.api.combat;

import dev.doctor4t.wathe.item.GrenadeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 手雷投掷模式与服务端爆炸公开 API。
 *
 * <p>{@link GrenadeItem} 的子类会自动被识别为可切换直投/蓄力模式；不方便继承该类的扩展物品，
 * 可以通过 {@link #registerThrowableItem(Identifier, Item)} 显式注册。爆炸 handler 的排序规则与枪击 API 一致。</p>
 */
public final class GrenadeApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<ExplosionEntry> EXPLOSION_COMPARATOR =
            Comparator.<ExplosionEntry>comparingInt(ExplosionEntry::priority).reversed()
                    .thenComparing(Comparator.comparingLong(ExplosionEntry::order).reversed());
    private static final Map<Identifier, Item> THROWABLE_ITEMS = new LinkedHashMap<>();
    private static final List<ExplosionEntry> EXPLOSION_HANDLERS = new ArrayList<>();
    private static long nextOrder;

    private GrenadeApi() {
    }

    public static void registerThrowableItem(@NotNull Identifier id, @NotNull Item item) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        synchronized (THROWABLE_ITEMS) {
            THROWABLE_ITEMS.put(id, item);
        }
    }

    public static boolean isThrowableItem(@NotNull ItemStack stack) {
        if (stack.getItem() instanceof GrenadeItem) {
            return true;
        }
        synchronized (THROWABLE_ITEMS) {
            return THROWABLE_ITEMS.containsValue(stack.getItem());
        }
    }

    public static void registerExplosionHandler(
            @NotNull Identifier id,
            int priority,
            @NotNull ExplosionHandler handler
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (EXPLOSION_HANDLERS) {
            EXPLOSION_HANDLERS.removeIf(entry -> entry.id().equals(id));
            EXPLOSION_HANDLERS.add(new ExplosionEntry(id, priority, nextOrder++, handler));
            EXPLOSION_HANDLERS.sort(EXPLOSION_COMPARATOR);
        }
    }

    public static @NotNull GrenadeExplosionResult handleExplosion(@NotNull GrenadeExplosionContext context) {
        for (ExplosionEntry entry : explosionSnapshot()) {
            GrenadeExplosionResult result = entry.handler().handle(context);
            if (result != null && result != GrenadeExplosionResult.PASS) {
                return result;
            }
        }
        return GrenadeExplosionResult.PASS;
    }

    private static List<ExplosionEntry> explosionSnapshot() {
        synchronized (EXPLOSION_HANDLERS) {
            return List.copyOf(EXPLOSION_HANDLERS);
        }
    }

    @FunctionalInterface
    public interface ExplosionHandler {
        @NotNull GrenadeExplosionResult handle(@NotNull GrenadeExplosionContext context);
    }

    private record ExplosionEntry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull ExplosionHandler handler
    ) {
    }
}
