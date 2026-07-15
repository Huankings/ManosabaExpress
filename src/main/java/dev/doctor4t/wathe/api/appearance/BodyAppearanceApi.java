package dev.doctor4t.wathe.api.appearance;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wathe 尸体生成时的公开外观接入点。
 *
 * <p>这个 API 只决定“新生成尸体看起来像谁”，不会改变尸体的真实 owner。
 * {@code PlayerBodyEntity#getPlayerUuid()} 仍然表示真正死亡的玩家，验尸、尸袋、回放等逻辑都继续读它；
 * 扩展职业如果只是想改玩家肉眼看到的尸体皮肤，应返回一个外观 UUID。</p>
 *
 * <p>priority 越大越先执行；返回 {@code null} 表示 PASS，把机会交给低优先级 handler。</p>
 */
public final class BodyAppearanceApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.<Entry>comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());

    private static final List<Entry> HANDLERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private BodyAppearanceApi() {
    }

    public static void register(@NotNull Identifier id, int priority, @NotNull Handler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (HANDLERS) {
            HANDLERS.removeIf(entry -> entry.id().equals(id));
            HANDLERS.add(new Entry(id, priority, nextOrder++, handler));
            HANDLERS.sort(ENTRY_COMPARATOR);
        }
    }

    public static @Nullable UUID resolveAppearanceUuid(@NotNull PlayerEntity victim,
                                                       @Nullable PlayerEntity killer,
                                                       @NotNull Identifier deathReason) {
        for (Entry entry : snapshot()) {
            UUID result = entry.handler().getAppearanceUuid(victim, killer, deathReason);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static List<Entry> snapshot() {
        synchronized (HANDLERS) {
            return List.copyOf(HANDLERS);
        }
    }

    @FunctionalInterface
    public interface Handler {
        @Nullable UUID getAppearanceUuid(@NotNull PlayerEntity victim,
                                         @Nullable PlayerEntity killer,
                                         @NotNull Identifier deathReason);
    }

    private record Entry(@NotNull Identifier id, int priority, long order, @NotNull Handler handler) {
    }
}
