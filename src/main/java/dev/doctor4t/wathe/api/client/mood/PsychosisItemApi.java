package dev.doctor4t.wathe.api.client.mood;

import dev.doctor4t.wathe.api.Role;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.game.GameFunctions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.math.random.Random;

/**
 * 低心情“脑补手持物品”的客户端公开 API。
 *
 * <p>这个接口只改变观察者客户端看到的模型，不会修改目标玩家真实背包，
 * 也不能作为服务端攻击、交互或职业判定的依据。Wathe 自带的低心情幻觉拥有
 * {@link #DEFAULT_PRIORITY} 优先级；扩展 provider 使用更高优先级即可覆盖它，
 * 使用更低优先级则只有在 Wathe 默认幻觉本次没有结果时才有机会执行。</p>
 */
@Environment(EnvType.CLIENT)
public final class PsychosisItemApi {
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<ProviderEntry> COMPARATOR =
            Comparator.<ProviderEntry>comparingInt(ProviderEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ProviderEntry::order).reversed());
    private static final List<ProviderEntry> PROVIDERS = new ArrayList<>();
    private static long nextOrder = 0L;

    private PsychosisItemApi() {
    }

    /**
     * 注册一个可以接管幻觉手持物的 provider。
     *
     * <p>provider 返回 {@code null} 或 {@link Result#PASS} 时表示继续处理；
     * 返回其它 Result 则会同时决定显示物品和（可选的）手臂姿势。</p>
     */
    public static synchronized void registerProvider(
            @NotNull Identifier id,
            int priority,
            @NotNull Provider provider
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new ProviderEntry(id, priority, nextOrder++, provider));
        PROVIDERS.sort(COMPARATOR);
    }

    /** 判断当前是否存在扩展 provider，供心情组件决定是否需要在正常心情下尝试解析。 */
    public static synchronized boolean hasProviders() {
        return !PROVIDERS.isEmpty();
    }

    /**
     * 解析一次“重新抽取”结果。
     *
     * <p>Wathe 默认结果由调用方传入并固定视为 priority 0：先执行所有高于 0 的扩展，
     * 再执行 Wathe 默认结果；只有默认结果为 PASS 时，才继续执行 priority <= 0 的扩展。
     * 这正是“高优先级覆盖原机制、低优先级不抢原机制”的语义。</p>
     */
    public static @Nullable Result resolve(
            @NotNull Context context,
            @Nullable Result watheDefaultResult
    ) {
        for (ProviderEntry entry : providerSnapshot()) {
            if (entry.priority() <= DEFAULT_PRIORITY) {
                continue;
            }
            Result result = entry.provider().resolve(context);
            if (isHandled(result)) {
                return result;
            }
        }

        if (isHandled(watheDefaultResult)) {
            return watheDefaultResult;
        }

        for (ProviderEntry entry : providerSnapshot()) {
            if (entry.priority() > DEFAULT_PRIORITY) {
                continue;
            }
            Result result = entry.provider().resolve(context);
            if (isHandled(result)) {
                return result;
            }
        }
        return null;
    }

    private static boolean isHandled(@Nullable Result result) {
        return result != null && result.handled;
    }

    /**
     * 渲染层统一读取已缓存的幻觉物品。
     * 这里再次检查观察者和目标是否仍按 Wathe 玩法存活，避免死亡后的最后一帧继续使用旧缓存。
     */
    public static @NotNull ItemStack resolveRenderStack(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand,
            @NotNull ItemStack actualStack
    ) {
        if (!(holder instanceof PlayerEntity target) || !canRender(viewer, target)) {
            return actualStack;
        }
        PlayerMoodComponent mood = PlayerMoodComponent.KEY.get(viewer);
        ItemStack psychosis = mood.getPsychosisItem(target.getUuid(), hand);
        return psychosis == null ? actualStack : psychosis;
    }

    /** 返回已缓存的显式手臂姿势；null 表示沿用原版/物品推导姿势。 */
    public static @Nullable BipedEntityModel.ArmPose resolveRenderArmPose(
            @Nullable PlayerEntity viewer,
            @NotNull LivingEntity holder,
            @NotNull Hand hand
    ) {
        if (!(holder instanceof PlayerEntity target) || !canRender(viewer, target)) {
            return null;
        }
        String poseName = PlayerMoodComponent.KEY.get(viewer).getPsychosisArmPoseName(target.getUuid(), hand);
        if (poseName == null) {
            return null;
        }
        try {
            return BipedEntityModel.ArmPose.valueOf(poseName);
        } catch (IllegalArgumentException ignored) {
            // 扩展卸载/热替换后可能留下未知枚举名；未知姿势必须安全回退到原版。
            return null;
        }
    }

    private static boolean canRender(@Nullable PlayerEntity viewer, @NotNull PlayerEntity target) {
        if (viewer == null || viewer.getWorld() != target.getWorld()) {
            return false;
        }
        GameWorldComponent gameWorld = GameWorldComponent.KEY.get(viewer.getWorld());
        return gameWorld.isRunning()
                && GameFunctions.isPlayerAliveAndSurvival(viewer)
                && GameFunctions.isPlayerAliveAndSurvival(target);
    }

    private static synchronized List<ProviderEntry> providerSnapshot() {
        return List.copyOf(PROVIDERS);
    }

    /** provider 可返回 null 或 PASS 表示不接管。 */
    @FunctionalInterface
    public interface Provider {
        @Nullable Result resolve(@NotNull Context context);
    }

    /**
     * provider 上下文。随机选择必须使用这里提供的随机源，避免扩展自行读取错误世界或服务端随机源。
     */
    public record Context(
            @NotNull PlayerEntity viewer,
            @NotNull PlayerEntity target,
            @NotNull Hand hand,
            @NotNull ItemStack actualStack,
            @NotNull PlayerMoodComponent moodComponent,
            @NotNull GameWorldComponent gameWorld,
            @Nullable Role targetRole,
            boolean defaultHallucinationEnabled,
            boolean reroll,
            @NotNull Random random
    ) {
        public @Nullable Item randomItem(@NotNull Collection<Item> items) {
            if (items.isEmpty()) {
                return null;
            }
            return items.stream().skip(this.random.nextInt(items.size())).findFirst().orElse(null);
        }

        public @Nullable Item randomTaggedItem(@NotNull TagKey<Item> tag) {
            List<Item> items = this.target.getRegistryManager().createRegistryLookup()
                    .getOrThrow(net.minecraft.registry.RegistryKeys.ITEM)
                    .getOptional(tag)
                    .map(list -> list.stream().map(entry -> entry.value()).toList())
                    .orElse(List.of());
            return randomItem(items);
        }
    }

    /** 一次完整的物品/姿势覆盖结果。stack 会在写入心情缓存时再次 copy。 */
    public static final class Result {
        public static final Result PASS = new Result(false, ItemStack.EMPTY, null);

        private final boolean handled;
        private final ItemStack stack;
        private final @Nullable BipedEntityModel.ArmPose armPose;

        private Result(boolean handled, @NotNull ItemStack stack, @Nullable BipedEntityModel.ArmPose armPose) {
            this.handled = handled;
            this.stack = stack;
            this.armPose = armPose;
        }

        public static @NotNull Result item(@NotNull ItemStack stack) {
            return new Result(true, stack.copy(), null);
        }

        public static @NotNull Result item(@NotNull Item item) {
            return item(new ItemStack(item));
        }

        public static @NotNull Result itemWithPose(@NotNull ItemStack stack, @NotNull BipedEntityModel.ArmPose pose) {
            return new Result(true, stack.copy(), pose);
        }

        public static @NotNull Result emptyWithPose(@NotNull BipedEntityModel.ArmPose pose) {
            return new Result(true, ItemStack.EMPTY, pose);
        }

        public boolean handled() {
            return this.handled;
        }

        public @NotNull ItemStack stack() {
            return this.stack;
        }

        public @Nullable BipedEntityModel.ArmPose armPose() {
            return this.armPose;
        }
    }

    private record ProviderEntry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull Provider provider
    ) {
    }
}
