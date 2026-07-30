package dev.doctor4t.wathe.api.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.doctor4t.wathe.Wathe;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 准心图标渲染的公开客户端接入点。
 *
 * <p>这个 API 只负责屏幕中心那枚 crosshair 图标和贴在准心旁边的小型进度图标。
 * 准心名字、尸体文字、同伙提示等仍然应该走 {@link RoleNameHudApi}；右下角状态、全屏遮罩
 * 和狙击镜大遮罩仍然应该走 {@code HudOverlayApi}。这样扩展职业可以按 UI 类型选择最窄入口，
 * 不需要再 mixin Wathe 的 {@code CrosshairRenderer}。</p>
 */
@Environment(EnvType.CLIENT)
public final class CrosshairHudApi {
    public static final int DEFAULT_PRIORITY = 0;

    public static final Identifier CROSSHAIR = Wathe.id("hud/crosshair");
    public static final Identifier CROSSHAIR_TARGET = Wathe.id("hud/crosshair_target");
    public static final Identifier KNIFE_ATTACK = Wathe.id("hud/knife_attack");
    public static final Identifier KNIFE_PROGRESS = Wathe.id("hud/knife_progress");
    public static final Identifier KNIFE_BACKGROUND = Wathe.id("hud/knife_background");
    public static final Identifier BAT_ATTACK = Wathe.id("hud/bat_attack");
    public static final Identifier BAT_PROGRESS = Wathe.id("hud/bat_progress");
    public static final Identifier BAT_BACKGROUND = Wathe.id("hud/bat_background");

    /**
     * Provider 是短路链：高 priority 先执行，同 priority 下后注册者先执行。
     * 这让扩展可以用更高优先级完整接管某个物品的准心，避免多个职业同时争抢同一帧。
     */
    private static final Comparator<ProviderEntry> PROVIDER_COMPARATOR =
            Comparator.<ProviderEntry>comparingInt(ProviderEntry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(ProviderEntry::order).reversed());

    /**
     * Overlay 会全部渲染，并且后画的会盖住先画的，所以 priority 越大越晚渲染。
     * 这类入口只适合“默认准心之后再补一条进度条”的窄场景，不应用来替换默认准心。
     */
    private static final Comparator<OverlayEntry> OVERLAY_COMPARATOR =
            Comparator.comparingInt(OverlayEntry::priority)
                    .thenComparingLong(OverlayEntry::order);

    private static final List<ProviderEntry> PROVIDERS = new ArrayList<>();
    private static final List<OverlayEntry> OVERLAYS = new ArrayList<>();
    private static long nextOrder = 0L;

    private CrosshairHudApi() {
    }

    /**
     * 注册一个可以接管默认准心的 provider。
     *
     * <p>Provider 返回 {@link Result#PASS} 表示“不处理，继续询问低优先级 provider 或 Wathe 默认准心”；
     * 返回 {@link Result#HANDLED} 表示“本帧准心已经由 provider 处理完”。HANDLED 可以是已经画完自定义准心，
     * 也可以是狙击枪开镜那样故意隐藏默认准心。</p>
     */
    public static void registerProvider(@NotNull Identifier id, int priority, @NotNull CrosshairProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        synchronized (PROVIDERS) {
            PROVIDERS.removeIf(entry -> entry.id().equals(id));
            PROVIDERS.add(new ProviderEntry(id, priority, nextOrder(), provider));
            PROVIDERS.sort(PROVIDER_COMPARATOR);
        }
    }

    /**
     * 注册一个默认准心之后的附加绘制器。
     *
     * <p>Overlay 不会阻止 Wathe 默认准心，也不会阻止其他 overlay。
     * 时停者怀表这类“准心本身不变，只在下方显示冷却/蓄力条”的功能应使用这里。</p>
     */
    public static void registerOverlay(@NotNull Identifier id, int priority, @NotNull CrosshairOverlay overlay) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(overlay, "overlay");
        synchronized (OVERLAYS) {
            OVERLAYS.removeIf(entry -> entry.id().equals(id));
            OVERLAYS.add(new OverlayEntry(id, priority, nextOrder(), overlay));
            OVERLAYS.sort(OVERLAY_COMPARATOR);
        }
    }

    public static @NotNull Result renderProvider(@NotNull Context context) {
        for (ProviderEntry entry : providerSnapshot()) {
            Result result = entry.provider().render(context);
            if (result != null && result != Result.PASS) {
                return result;
            }
        }
        return Result.PASS;
    }

    public static void renderOverlays(@NotNull Context context) {
        for (OverlayEntry entry : overlaySnapshot()) {
            entry.overlay().render(context);
        }
    }

    /**
     * 渲染 Wathe 标准 3x3 准心，矩阵会自动移动到屏幕中心。
     */
    public static void renderStandardCrosshair(@NotNull Context context, boolean target) {
        renderCentered(context, centered -> drawCrosshairIcon(centered, target));
    }

    /**
     * 渲染“匕首同款”准心：准心下方使用 Wathe 的 knife_attack / knife_progress 资源。
     *
     * @param highlightCrosshair 是否把 3x3 准心切成锁定目标贴图
     * @param showAttackIcon     是否在下方显示 ready/attack 图标；为 false 时显示进度条
     * @param progress           进度条填充比例，0 到 1
     */
    public static void renderKnifeProgressCrosshair(@NotNull Context context,
                                                    boolean highlightCrosshair,
                                                    boolean showAttackIcon,
                                                    float progress) {
        renderCentered(context, centered -> {
            drawKnifeProgressIcon(centered, showAttackIcon, progress);
            drawCrosshairIcon(centered, highlightCrosshair);
        });
    }

    /**
     * 渲染“疯魔近战同款”准心：准心下方使用 Wathe 的 bat_attack / bat_progress 资源。
     */
    public static void renderBatProgressCrosshair(@NotNull Context context,
                                                  boolean highlightCrosshair,
                                                  boolean showAttackIcon,
                                                  float progress) {
        renderCentered(context, centered -> {
            drawBatProgressIcon(centered, showAttackIcon, progress);
            drawCrosshairIcon(centered, highlightCrosshair);
        });
    }

    /**
     * 渲染扩展自定义的 10x7 ready/progress 小图标，并复用 Wathe 的 3x3 准心。
     *
     * <p>这个 helper 专门给小偷、追忆者这类“图标是自己的，但准心图标仍然沿用 Wathe 风格”
     * 的职业使用。服务端技能判定仍然必须在对应 C2S 包或物品逻辑里重新校验。</p>
     */
    public static void renderIconProgressCrosshair(@NotNull Context context,
                                                   boolean highlightCrosshair,
                                                   boolean showReadyIcon,
                                                   float progress,
                                                   @NotNull Identifier readyTexture,
                                                   @NotNull Identifier backgroundTexture,
                                                   @NotNull Identifier fillTexture) {
        Objects.requireNonNull(readyTexture, "readyTexture");
        Objects.requireNonNull(backgroundTexture, "backgroundTexture");
        Objects.requireNonNull(fillTexture, "fillTexture");
        renderCentered(context, centered -> {
            drawIconProgress(centered, showReadyIcon, progress, readyTexture, backgroundTexture, fillTexture);
            drawCrosshairIcon(centered, highlightCrosshair);
        });
    }

    /**
     * 在屏幕中心坐标系下执行一段绘制逻辑。
     *
     * <p>传入的 renderer 运行时，矩阵原点已经在屏幕中心。Wathe 标准准心图标使用
     * {@code (-1.5, -1.5)} 到 {@code (1.5, 1.5)} 的 3x3 区域；下方小图标通常使用
     * {@code -5, 5, 10, 7}。把这段矩阵和 RenderSystem 状态收口在 API 内，
     * 是为了避免扩展职业继续复制易错的 blend 设置。</p>
     */
    public static void renderCentered(@NotNull Context context, @NotNull CenteredRenderer renderer) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(renderer, "renderer");
        DrawContext drawContext = context.drawContext();
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(context.centerX(), context.centerY(), 0.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        renderer.render(context);
        drawContext.getMatrices().pop();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /**
     * 在已经居中的矩阵下绘制 Wathe 标准 3x3 准心。
     */
    public static void drawCrosshairIcon(@NotNull Context context, boolean target) {
        DrawContext drawContext = context.drawContext();
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(-1.5F, -1.5F, 0.0F);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SrcFactor.ONE_MINUS_DST_COLOR,
                GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SrcFactor.ONE,
                GlStateManager.DstFactor.ZERO
        );
        drawContext.drawGuiTexture(target ? CROSSHAIR_TARGET : CROSSHAIR, 0, 0, 3, 3);
        drawContext.getMatrices().pop();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    public static void drawKnifeProgressIcon(@NotNull Context context, boolean showAttackIcon, float progress) {
        drawIconProgress(context, showAttackIcon, progress, KNIFE_ATTACK, KNIFE_BACKGROUND, KNIFE_PROGRESS);
    }

    public static void drawBatProgressIcon(@NotNull Context context, boolean showAttackIcon, float progress) {
        drawIconProgress(context, showAttackIcon, progress, BAT_ATTACK, BAT_BACKGROUND, BAT_PROGRESS);
    }

    public static void drawIconProgress(@NotNull Context context,
                                        boolean showReadyIcon,
                                        float progress,
                                        @NotNull Identifier readyTexture,
                                        @NotNull Identifier backgroundTexture,
                                        @NotNull Identifier fillTexture) {
        DrawContext drawContext = context.drawContext();
        if (showReadyIcon) {
            drawContext.drawGuiTexture(readyTexture, -5, 5, 10, 7);
            return;
        }

        int fillWidth = Math.max(0, Math.min(10, (int) (MathHelper.clamp(progress, 0.0F, 1.0F) * 10.0F)));
        drawContext.drawGuiTexture(backgroundTexture, -5, 5, 10, 7);
        drawContext.drawGuiTexture(fillTexture, 10, 7, 0, 0, -5, 5, fillWidth, 7);
    }

    private static List<ProviderEntry> providerSnapshot() {
        synchronized (PROVIDERS) {
            return List.copyOf(PROVIDERS);
        }
    }

    private static List<OverlayEntry> overlaySnapshot() {
        synchronized (OVERLAYS) {
            return List.copyOf(OVERLAYS);
        }
    }

    private static synchronized long nextOrder() {
        return nextOrder++;
    }

    public record Context(@NotNull MinecraftClient client,
                          @NotNull ClientPlayerEntity player,
                          @NotNull DrawContext drawContext,
                          @NotNull RenderTickCounter tickCounter,
                          @NotNull ItemStack mainHandStack,
                          float tickDelta) {
        public int centerX() {
            return this.drawContext.getScaledWindowWidth() / 2;
        }

        public int centerY() {
            return this.drawContext.getScaledWindowHeight() / 2;
        }
    }

    @FunctionalInterface
    public interface CrosshairProvider {
        @NotNull Result render(@NotNull Context context);
    }

    @FunctionalInterface
    public interface CrosshairOverlay {
        void render(@NotNull Context context);
    }

    @FunctionalInterface
    public interface CenteredRenderer {
        void render(@NotNull Context context);
    }

    public enum Result {
        PASS,
        HANDLED
    }

    private record ProviderEntry(@NotNull Identifier id,
                                 int priority,
                                 long order,
                                 @NotNull CrosshairProvider provider) {
    }

    private record OverlayEntry(@NotNull Identifier id,
                                int priority,
                                long order,
                                @NotNull CrosshairOverlay overlay) {
    }
}
