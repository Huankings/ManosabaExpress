package dev.doctor4t.wathe.api.client.input;

import dev.doctor4t.wathe.mixin.client.input.KeyBindingAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Wathe 客户端键盘、鼠标、KeyBinding 与最终游戏动作的统一公开接入点。
 *
 * <p>扩展职业只需要注册规则，不应再分别 mixin {@code KeyBinding}、{@code Keyboard}、
 * {@code Mouse} 或 {@code MinecraftClient#doAttack/doItemUse}。Wathe 在唯一的原版注入点
 * 中按优先级调度这里的规则，从而避免多个扩展争抢 mixin priority，或因为加载顺序不同而
 * 出现某个状态能拦攻击、却拦不住滚轮和短按队列的情况。</p>
 *
 * <p>所有规则均为纯客户端体验层。真实攻击、物品使用和职业能力仍必须在服务端重新校验；
 * 客户端 BLOCK 只能防止本地输入进入原版执行链，不能作为权限边界。</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientInputApi {
    /** Wathe 自带局内键位限制使用的优先级。扩展覆盖规则通常使用大于 0 的值。 */
    public static final int DEFAULT_PRIORITY = 0;

    private static final Comparator<Entry<?>> ENTRY_COMPARATOR =
            Comparator.<Entry<?>, Integer>comparing(Entry::priority)
                    .reversed()
                    .thenComparing(Comparator.comparingLong((Entry<?> entry) -> entry.order()).reversed());

    private static final List<Entry<KeyBindingRule>> KEY_BINDING_RULES = new ArrayList<>();
    private static final List<Entry<KeyboardRule>> KEYBOARD_RULES = new ArrayList<>();
    private static final List<Entry<MouseButtonRule>> MOUSE_BUTTON_RULES = new ArrayList<>();
    private static final List<Entry<MouseScrollRule>> MOUSE_SCROLL_RULES = new ArrayList<>();
    private static final List<Entry<ActionRule>> ACTION_RULES = new ArrayList<>();
    private static final List<Entry<InputTickHandler>> INPUT_TICK_HANDLERS = new ArrayList<>();
    private static long nextOrder;

    private ClientInputApi() {
    }

    public static void registerKeyBindingRule(@NotNull Identifier id, int priority, @NotNull KeyBindingRule rule) {
        register(KEY_BINDING_RULES, id, priority, rule);
    }

    public static void registerKeyboardRule(@NotNull Identifier id, int priority, @NotNull KeyboardRule rule) {
        register(KEYBOARD_RULES, id, priority, rule);
    }

    public static void registerMouseButtonRule(@NotNull Identifier id, int priority, @NotNull MouseButtonRule rule) {
        register(MOUSE_BUTTON_RULES, id, priority, rule);
    }

    public static void registerMouseScrollRule(@NotNull Identifier id, int priority, @NotNull MouseScrollRule rule) {
        register(MOUSE_SCROLL_RULES, id, priority, rule);
    }

    /**
     * 注册最终游戏动作规则。
     *
     * <p>该入口位于原版真正执行攻击、使用、选取方块和持续挖掘之前，用来兜住“状态同步到
     * 客户端的同一帧，点击已经进入 handleInputEvents”的竞态。返回 BLOCK 会取消本次动作；
     * 返回 ALLOW 会停止低优先级规则并保留原版动作；PASS 继续询问后续规则。</p>
     */
    public static void registerActionRule(@NotNull Identifier id, int priority, @NotNull ActionRule rule) {
        register(ACTION_RULES, id, priority, rule);
    }

    /**
     * 注册每次 {@code MinecraftClient.handleInputEvents} 开始时的维护回调。
     *
     * <p>它专门用于清理 pressed、timesPressed 或正在使用物品等客户端残留状态，不负责
     * 决定动作是否允许。动作是否取消仍由 {@link #registerActionRule} 明确注册。</p>
     */
    public static void registerInputTickHandler(@NotNull Identifier id, int priority, @NotNull InputTickHandler handler) {
        register(INPUT_TICK_HANDLERS, id, priority, handler);
    }

    public static boolean resolveKeyBinding(
            @NotNull KeyBinding keyBinding,
            @NotNull KeyQuery query,
            boolean originalResult,
            int keyCode,
            int scanCode
    ) {
        KeyBindingContext context = new KeyBindingContext(
                MinecraftClient.getInstance(), keyBinding, query, originalResult, keyCode, scanCode
        );
        for (Entry<KeyBindingRule> entry : snapshot(KEY_BINDING_RULES)) {
            KeyResult result = entry.handler().resolve(context);
            if (result == null || result.decision() == Decision.PASS) {
                continue;
            }
            return switch (result.decision()) {
                case BLOCK -> false;
                case ALLOW -> originalResult;
                case OVERRIDE -> result.value();
                case PASS -> originalResult;
            };
        }
        return originalResult;
    }

    public static boolean shouldBlockKeyboard(int key, int scanCode, int action, int modifiers) {
        KeyboardContext context = new KeyboardContext(MinecraftClient.getInstance(), key, scanCode, action, modifiers);
        return resolveDecision(KEYBOARD_RULES, rule -> rule.resolve(context)) == Decision.BLOCK;
    }

    public static boolean shouldBlockMouseButton(int button, int action, int modifiers) {
        MouseButtonContext context = new MouseButtonContext(MinecraftClient.getInstance(), button, action, modifiers);
        return resolveDecision(MOUSE_BUTTON_RULES, rule -> rule.resolve(context)) == Decision.BLOCK;
    }

    public static boolean shouldBlockMouseScroll(double horizontal, double vertical) {
        MouseScrollContext context = new MouseScrollContext(MinecraftClient.getInstance(), horizontal, vertical);
        return resolveDecision(MOUSE_SCROLL_RULES, rule -> rule.resolve(context)) == Decision.BLOCK;
    }

    public static @NotNull ActionResult resolveAction(@NotNull ClientAction action, boolean breaking) {
        ActionContext context = new ActionContext(MinecraftClient.getInstance(), action, breaking);
        for (Entry<ActionRule> entry : snapshot(ACTION_RULES)) {
            ActionResult result = entry.handler().resolve(context);
            if (result != null && result.decision() != Decision.PASS) {
                return result;
            }
        }
        return ActionResult.pass();
    }

    public static void runInputTickHandlers() {
        InputTickContext context = new InputTickContext(MinecraftClient.getInstance());
        for (Entry<InputTickHandler> entry : snapshot(INPUT_TICK_HANDLERS)) {
            entry.handler().handle(context);
        }
    }

    /** 清掉 KeyBinding 尚未消费的短按次数，但保留当前物理按住状态。 */
    public static void clearPressQueue(@Nullable KeyBinding keyBinding) {
        if (keyBinding != null) {
            ((KeyBindingAccessor) keyBinding).wathe$setTimesPressed(0);
        }
    }

    /** 同时释放 pressed 并清空短按队列，适合攻击、使用、背包和选槽等离散动作。 */
    public static void releaseKey(@Nullable KeyBinding keyBinding) {
        if (keyBinding == null) {
            return;
        }
        keyBinding.setPressed(false);
        clearPressQueue(keyBinding);
    }

    /**
     * 按玩家当前真实键位配置读取物理输入，兼容键盘键、扫描码和鼠标键。
     *
     * <p>该方法只用于特殊相机或高优先级规则恢复真实物理状态；普通能力输入仍应读取
     * KeyBinding，避免绕开其它输入锁。</p>
     */
    public static boolean isPhysicallyPressed(@Nullable MinecraftClient client, @Nullable KeyBinding keyBinding) {
        if (client == null || keyBinding == null || client.getWindow() == null) {
            return false;
        }
        InputUtil.Key boundKey = ((KeyBindingAccessor) keyBinding).wathe$getBoundKey();
        long window = client.getWindow().getHandle();
        if (boundKey.getCategory() == InputUtil.Type.KEYSYM || boundKey.getCategory() == InputUtil.Type.SCANCODE) {
            return InputUtil.isKeyPressed(window, boundKey.getCode());
        }
        if (boundKey.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, boundKey.getCode()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private static <T> void register(List<Entry<T>> entries, Identifier id, int priority, T handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        synchronized (entries) {
            entries.removeIf(entry -> entry.id().equals(id));
            entries.add(new Entry<>(id, priority, nextOrder++, handler));
            entries.sort(ENTRY_COMPARATOR);
        }
    }

    private static <T> List<Entry<T>> snapshot(List<Entry<T>> entries) {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    private static <T> Decision resolveDecision(List<Entry<T>> entries, DecisionResolver<T> resolver) {
        for (Entry<T> entry : snapshot(entries)) {
            Decision decision = resolver.resolve(entry.handler());
            if (decision != null && decision != Decision.PASS) {
                return decision;
            }
        }
        return Decision.PASS;
    }

    public enum Decision {
        PASS,
        ALLOW,
        BLOCK,
        OVERRIDE
    }

    public enum KeyQuery {
        WAS_PRESSED,
        IS_PRESSED,
        MATCHES_KEY
    }

    public enum ClientAction {
        ATTACK,
        ITEM_USE,
        ITEM_PICK,
        BLOCK_BREAKING
    }

    public record KeyResult(@NotNull Decision decision, boolean value) {
        private static final KeyResult PASS = new KeyResult(Decision.PASS, false);
        private static final KeyResult ALLOW = new KeyResult(Decision.ALLOW, false);
        private static final KeyResult BLOCK = new KeyResult(Decision.BLOCK, false);

        public static KeyResult pass() {
            return PASS;
        }

        public static KeyResult allow() {
            return ALLOW;
        }

        public static KeyResult block() {
            return BLOCK;
        }

        public static KeyResult override(boolean value) {
            return new KeyResult(Decision.OVERRIDE, value);
        }
    }

    /**
     * 最终动作解析结果。
     *
     * <p>HANDLED 用于扩展已经自行完成动作的窄场景，例如先通过攻击专用射线找到目标、发送
     * 自定义 C2S 包后取消原版 doAttack。其 value 只对返回 boolean 的 ATTACK 有意义；其它
     * void 动作遇到 HANDLED 时与 BLOCK 一样取消原版执行。</p>
     */
    public record ActionResult(@NotNull Decision decision, boolean value) {
        private static final ActionResult PASS = new ActionResult(Decision.PASS, false);
        private static final ActionResult ALLOW = new ActionResult(Decision.ALLOW, false);
        private static final ActionResult BLOCK = new ActionResult(Decision.BLOCK, false);

        public static ActionResult pass() {
            return PASS;
        }

        public static ActionResult allow() {
            return ALLOW;
        }

        public static ActionResult block() {
            return BLOCK;
        }

        public static ActionResult handled(boolean value) {
            return new ActionResult(Decision.OVERRIDE, value);
        }
    }

    public record KeyBindingContext(
            @NotNull MinecraftClient client,
            @NotNull KeyBinding keyBinding,
            @NotNull KeyQuery query,
            boolean originalResult,
            int keyCode,
            int scanCode
    ) {
    }

    public record KeyboardContext(
            @NotNull MinecraftClient client,
            int key,
            int scanCode,
            int action,
            int modifiers
    ) {
    }

    public record MouseButtonContext(
            @NotNull MinecraftClient client,
            int button,
            int action,
            int modifiers
    ) {
    }

    public record MouseScrollContext(
            @NotNull MinecraftClient client,
            double horizontal,
            double vertical
    ) {
    }

    public record ActionContext(
            @NotNull MinecraftClient client,
            @NotNull ClientAction action,
            boolean breaking
    ) {
    }

    public record InputTickContext(@NotNull MinecraftClient client) {
    }

    @FunctionalInterface
    public interface KeyBindingRule {
        @Nullable KeyResult resolve(@NotNull KeyBindingContext context);
    }

    @FunctionalInterface
    public interface KeyboardRule {
        @Nullable Decision resolve(@NotNull KeyboardContext context);
    }

    @FunctionalInterface
    public interface MouseButtonRule {
        @Nullable Decision resolve(@NotNull MouseButtonContext context);
    }

    @FunctionalInterface
    public interface MouseScrollRule {
        @Nullable Decision resolve(@NotNull MouseScrollContext context);
    }

    @FunctionalInterface
    public interface ActionRule {
        @Nullable ActionResult resolve(@NotNull ActionContext context);
    }

    @FunctionalInterface
    public interface InputTickHandler {
        void handle(@NotNull InputTickContext context);
    }

    @FunctionalInterface
    private interface DecisionResolver<T> {
        @Nullable Decision resolve(T handler);
    }

    private record Entry<T>(@NotNull Identifier id, int priority, long order, @NotNull T handler) {
    }
}
