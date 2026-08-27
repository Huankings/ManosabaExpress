package dev.doctor4t.wathe.api.client.fog;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FogShape;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 客户端最终雾效的公开接入点。
 *
 * <p>原版和地图增强会先通过 {@code BackgroundRenderer.applyFog} 写入基础雾状态，
 * 然后由这里按优先级询问扩展职业或其它客户端功能，最后再把最终的 start/end/shape 写回
 * {@link RenderSystem}。Iris 的标准 {@code FogUniforms} 会从 RenderSystem getter 读取这份最终状态，
 * 所以 shaderpack 不会重新拿回 Sodium 的普通视距。</p>
 *
 * <p>这里故意不直接依赖 Iris 的类。Iris 是可选模组，直接把 Iris 私有实现写进 Wathe 的核心
 * 渲染链会让没有 Iris 的客户端在加载 mixin 时出现目标类问题；使用 RenderSystem 的公开状态
 * 作为双方共同边界，既兼容原版，也兼容 Iris 的 legacy fog uniform 路径。</p>
 */
@Environment(EnvType.CLIENT)
public final class FogOverrideApi {
    /**
     * 默认优先级。扩展职业通常应使用自己的高优先级常量，避免被地图雾覆盖。
     */
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry> ENTRY_COMPARATOR =
            Comparator.<Entry>comparingInt(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(Entry::order).reversed());

    private static final List<Entry> PROVIDERS = new ArrayList<>();
    private static long nextOrder;

    /**
     * 这里只保存“本帧已经解析出的最终雾值”。
     *
     * <p>WorldRenderer 每帧开始时会清空它，使 provider 在读取基础雾距时拿到原版/地图刚写入的
     * 原始值；applyOverrides 完成后再保存最终值。RenderSystem getter mixin 只应该在世界渲染
     * 阶段返回这份最终值，帧末必须清掉，否则 GUI 文字 shader 也会被世界雾颜色影响。</p>
     */
    private static float resolvedStart = Float.NaN;
    private static float resolvedEnd = Float.NaN;
    private static @Nullable FogShape resolvedShape;

    private FogOverrideApi() {
    }

    /**
     * 注册一个雾效 provider。
     *
     * <p>priority 越大越先执行，同优先级下后注册的 provider 先执行；同一个 id 重复注册会替换旧
     * provider。返回 {@link FogOverride#pass()} 表示不接管本帧，继续交给低优先级 provider。</p>
     */
    public static synchronized void registerProvider(
            @NotNull Identifier id,
            int priority,
            @NotNull FogProvider provider
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        PROVIDERS.removeIf(entry -> entry.id().equals(id));
        PROVIDERS.add(new Entry(id, priority, nextOrder++, provider));
        PROVIDERS.sort(ENTRY_COMPARATOR);
    }

    /**
     * 在每帧 WorldRenderer 开始时清掉上一帧的 override。
     *
     * <p>如果不清理，下一帧的 provider 在读取基础雾距时会把上一帧的杰森雾距当成基础值，
     * 进出过渡就会不断向目标值重复插值，最终出现视距残留或无法恢复原版雾距。</p>
     */
    public static void beginFrame() {
        resolvedStart = Float.NaN;
        resolvedEnd = Float.NaN;
        resolvedShape = null;
    }

    /**
     * 在原版/地图雾完成后解析并应用最终雾效。
     *
     * @param camera    当前渲染相机，供需要按相机状态判断的扩展使用
     * @param tickDelta 当前帧的小数 tick，供平滑过渡使用
     */
    public static void applyOverrides(@NotNull Camera camera, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        float baseStart = RenderSystem.getShaderFogStart();
        float baseEnd = RenderSystem.getShaderFogEnd();
        FogShape baseShape = RenderSystem.getShaderFogShape();
        FogContext context = new FogContext(client, camera, tickDelta, baseStart, baseEnd, baseShape);

        FogOverride override = FogOverride.pass();
        for (Entry entry : providerSnapshot()) {
            FogOverride candidate = entry.provider().resolve(context);
            if (candidate != null && candidate.action() != Action.PASS) {
                override = candidate;
                break;
            }
        }

        if (override.action() == Action.PASS) {
            return;
        }

        float start = sanitizeStart(override.start(), baseStart);
        float end = sanitizeEnd(override.end(), baseEnd, start);
        FogShape shape = override.shape() == null ? baseShape : override.shape();

        /*
         * start 必须严格小于 end。除了避免原版渲染器的除零问题，也能避免 shaderpack 在收到
         * 非法 fog range 后把整张画面变成纯色。这里的最小间距是渲染兜底，不是玩法常量。
         */
        if (end <= start) {
            start = Math.max(0.0F, end - 0.01F);
        }

        resolvedStart = start;
        resolvedEnd = end;
        resolvedShape = shape;
        RenderSystem.setShaderFogStart(start);
        RenderSystem.setShaderFogEnd(end);
        RenderSystem.setShaderFogShape(shape);
    }

    /**
     * 在 world render 结束后切回 GUI 阶段的无雾状态。
     *
     * <p>1.21.1 的文字 shader 本身会读取 FogStart/FogEnd/FogColor。之前这里把 fog 还原成
     * “本帧世界基础雾”，结果聊天栏、tab 和 HUD 文本仍然会继续用白天/夜晚的世界雾颜色混合，
     * 看起来就像字在白天变白、晚上变黑。原版的 {@link BackgroundRenderer#clearFog()} 会把
     * FogStart 设成极大值，让 GUI 顶点距离永远落在“未起雾”区间，因此这里必须清成无雾，不能
     * 还原成世界雾。</p>
     */
    public static void endFrame() {
        BackgroundRenderer.clearFog();

        resolvedStart = Float.NaN;
        resolvedEnd = Float.NaN;
        resolvedShape = null;
    }

    /**
     * 给 RenderSystem getter mixin 读取本帧最终 start。
     *
     * <p>返回 NaN 表示本帧尚未有 override，调用方应继续使用 RenderSystem 原始字段。</p>
     */
    public static float getResolvedStartOrNaN() {
        return resolvedStart;
    }

    /**
     * 给 RenderSystem getter mixin 读取本帧最终 end。
     */
    public static float getResolvedEndOrNaN() {
        return resolvedEnd;
    }

    /**
     * 给 RenderSystem getter mixin 读取本帧最终 shape。
     */
    public static @Nullable FogShape getResolvedShapeOrNull() {
        return resolvedShape;
    }

    private static synchronized List<Entry> providerSnapshot() {
        return List.copyOf(PROVIDERS);
    }

    private static float sanitizeStart(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(0.0F, value) : fallback;
    }

    private static float sanitizeEnd(float value, float fallback, float start) {
        if (!Float.isFinite(value)) {
            return Math.max(start + 0.01F, fallback);
        }
        return Math.max(start + 0.01F, value);
    }

    @FunctionalInterface
    public interface FogProvider {
        @Nullable FogOverride resolve(@NotNull FogContext context);
    }

    /**
     * provider 运行时的基础渲染上下文。
     *
     * <p>baseStart/baseEnd/baseShape 已经包含原版、生物状态、液体和 Wathe 地图雾效果；
     * provider 只需要在真正接管时返回目标值，避免重写一份容易和原版分叉的雾效逻辑。</p>
     */
    public record FogContext(
            @NotNull MinecraftClient client,
            @NotNull Camera camera,
            float tickDelta,
            float baseStart,
            float baseEnd,
            @NotNull FogShape baseShape
    ) {
    }

    /**
     * provider 对本帧雾效的处理结果。
     */
    public record FogOverride(
            @NotNull Action action,
            float start,
            float end,
            @Nullable FogShape shape
    ) {
        private static final FogOverride PASS = new FogOverride(Action.PASS, 0.0F, 0.0F, null);

        public static @NotNull FogOverride pass() {
            return PASS;
        }

        public static @NotNull FogOverride override(float start, float end, @Nullable FogShape shape) {
            return new FogOverride(Action.OVERRIDE, start, end, shape);
        }

        public static @NotNull FogOverride override(float start, float end) {
            return override(start, end, FogShape.SPHERE);
        }
    }

    public enum Action {
        PASS,
        OVERRIDE
    }

    private record Entry(
            @NotNull Identifier id,
            int priority,
            long order,
            @NotNull FogProvider provider
    ) {
    }
}
