package dev.doctor4t.wathe.client.task;

import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.block_entity.SmallDoorBlockEntity;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.task.TaskPointType;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 客户端任务点透视渲染器。
 *
 * <p>表现层目标有三个：
 * 1. 线框要能穿墙看到；
 * 2. 靠近任务点时显示对应名称；
 * 3. 存活玩家只看自己当前任务相关的点，旁观/创造则可以看全部。
 */
public final class TaskPointOverlayRenderer {
    /**
     * 靠近多少格时开始显示任务点名称。
     */
    private static final double TASK_POINT_NAME_DISTANCE = 3.0D;

    /**
     * 自定义一个不进行深度测试的线框层，这样方块就能透视出来。
     */
    private static final RenderLayer TASK_POINT_LINES = RenderLayer.of(
            "wathe_task_point_lines",
            VertexFormats.LINES,
            VertexFormat.DrawMode.LINES,
            256,
            false,
            false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.LINES_PROGRAM)
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(4.0D)))
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .target(RenderPhase.ITEM_ENTITY_TARGET)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .build(false)
    );

    private TaskPointOverlayRenderer() {
    }

    public static void render(@NotNull WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        if (!TaskPointClientState.isTaskPointOverlayEnabled()) {
            return;
        }

        if (WatheClient.gameComponent == null || !WatheClient.gameComponent.isRunning()) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider vertexConsumers = context.consumers();
        if (matrices == null || vertexConsumers == null) {
            return;
        }

        EnumSet<TaskPointType> allowedTypes = WatheClient.isPlayerAliveAndInSurvival()
                ? TaskPointClientState.collectVisibleTypesForAlivePlayer(client.player)
                : EnumSet.allOf(TaskPointType.class);
        // 钥匙门透视不走“默认允许类型”，而是必须额外满足“当前手持匹配钥匙”这个条件。
        allowedTypes.remove(TaskPointType.KEYED_DOOR);
        String heldKeyName = getHeldKeyName(client.player.getMainHandStack());

        if (allowedTypes.isEmpty()) {
            // 即使当前没有任何任务点任务，也仍然可能因为手持钥匙而需要高亮对应的门。
            if (heldKeyName == null || heldKeyName.isEmpty()) {
                return;
            }
        }

        for (Map.Entry<BlockPos, EnumSet<TaskPointType>> entry : TaskPointClientState.createSnapshot().entrySet()) {
            EnumSet<TaskPointType> visibleTypes = EnumSet.copyOf(entry.getValue());
            visibleTypes.retainAll(allowedTypes);

            /**
             * 钥匙门透视是独立于任务刷新系统之外的额外规则：
             * 只要任务点透视开着，并且玩家当前主手拿着有名字的钥匙，
             * 就会把能匹配上这把钥匙名字的门额外加入可见集合。
             */
            if (entry.getValue().contains(TaskPointType.KEYED_DOOR) && isMatchingHeldKeyDoor(client, entry.getKey(), heldKeyName)) {
                visibleTypes.add(TaskPointType.KEYED_DOOR);
            }

            if (visibleTypes.isEmpty()) {
                continue;
            }

            renderTaskPoint(context, entry.getKey(), visibleTypes);
        }
    }

    /**
     * 渲染单个任务点。
     */
    private static void renderTaskPoint(
            @NotNull WorldRenderContext context,
            @NotNull BlockPos pos,
            @NotNull EnumSet<TaskPointType> visibleTypes
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || context.consumers() == null || context.matrixStack() == null) {
            return;
        }

        BlockState state = client.world.getBlockState(pos);
        Box localBox = getCombinedLocalBox(client.world, pos, state);
        int color = blendColors(visibleTypes);
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        VertexConsumer vertexConsumer = context.consumers().getBuffer(TASK_POINT_LINES);
        MatrixStack matrices = context.matrixStack();
        Vec3d cameraPos = context.camera().getPos();

        matrices.push();
        matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
        WorldRenderer.drawBox(matrices, vertexConsumer, localBox, red, green, blue, 1.0F);
        matrices.pop();

        if (cameraPos.squaredDistanceTo(pos.toCenterPos()) <= TASK_POINT_NAME_DISTANCE * TASK_POINT_NAME_DISTANCE) {
            renderTaskPointLabel(context, pos, localBox, buildTaskPointLabel(pos, visibleTypes), color);
        }
    }

    /**
     * 在任务点附近渲染名称。
     *
     * <p>文字使用 SEE_THROUGH 图层，配合正对玩家的朝向处理，
     * 这样不需要靠墙角才能看见说明。
     */
    private static void renderTaskPointLabel(
            @NotNull WorldRenderContext context,
            @NotNull BlockPos pos,
            @NotNull Box localBox,
            @NotNull Text label,
            int color
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || context.matrixStack() == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d cameraPos = context.camera().getPos();
        TextRenderer textRenderer = client.textRenderer;

        double centerX = (localBox.minX + localBox.maxX) * 0.5D;
        double topY = localBox.maxY + 0.35D;
        double centerZ = (localBox.minZ + localBox.maxZ) * 0.5D;
        /**
         * 文本颜色这里必须补上不透明 alpha。
         *
         * <p>前面线框渲染用的是分离出来的 RGB 浮点，所以只保留 0xRRGGBB 没问题；
         * 但 TextRenderer.draw 读取的是 ARGB 整数颜色。
         * 如果直接把 0xRRGGBB 传进去，高 8 位 alpha 就是 0，文本会完全透明，
         * 这也是之前“靠近透视点没有任何文字”的直接原因。
         */
        int opaqueTextColor = 0xFF000000 | color;

        matrices.push();
        matrices.translate(
                pos.getX() + centerX - cameraPos.x,
                pos.getY() + topY - cameraPos.y,
                pos.getZ() + centerZ - cameraPos.z
        );
        matrices.multiply(context.camera().getRotation());
        matrices.scale(-0.025F, -0.025F, 0.025F);

        float textX = -textRenderer.getWidth(label) / 2.0F;
        VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
        textRenderer.draw(
                label,
                textX,
                0.0F,
                opaqueTextColor,
                false,
                matrices.peek().getPositionMatrix(),
                immediate,
                TextRenderer.TextLayerType.SEE_THROUGH,
                0,
                15728880
        );
        immediate.draw();
        matrices.pop();
    }

    /**
     * 把多个任务点类型的名字拼成一个提示文字。
     *
     * <p>例如同一个托盘同时既是“生食托盘”又是“燃料托盘”时，
     * 靠近后会直接显示两个用途，方便你一眼看懂这个点为什么被高亮。
     */
    private static @NotNull Text buildTaskPointLabel(@NotNull BlockPos pos, @NotNull EnumSet<TaskPointType> visibleTypes) {
        MinecraftClient client = MinecraftClient.getInstance();

        /**
         * 对于钥匙门，靠近后优先显示门上实际绑定的房间名 / 钥匙名，
         * 这样玩家不需要额外猜“这扇高亮的门到底是哪把钥匙对应的门”。
         */
        if (visibleTypes.size() == 1 && visibleTypes.contains(TaskPointType.KEYED_DOOR) && client.world != null
                && client.world.getBlockEntity(pos) instanceof SmallDoorBlockEntity doorBlockEntity
                && !doorBlockEntity.getKeyName().isEmpty()) {
            return Text.translatable(TaskPointType.KEYED_DOOR.getTranslationKey())
                    .append(Text.literal(": "))
                    .append(Text.literal(doorBlockEntity.getKeyName()));
        }

        MutableText label = Text.empty();
        ArrayList<TaskPointType> orderedTypes = new ArrayList<>(visibleTypes);

        for (int i = 0; i < orderedTypes.size(); i++) {
            if (i > 0) {
                label.append(Text.literal(" / "));
            }
            label.append(Text.translatable(orderedTypes.get(i).getTranslationKey()));
        }

        return label;
    }

    /**
     * 取多个可见任务点类型的平均颜色，让多用途点不会只表现成某一种任务颜色。
     */
    private static int blendColors(@NotNull EnumSet<TaskPointType> visibleTypes) {
        int red = 0;
        int green = 0;
        int blue = 0;

        for (TaskPointType type : visibleTypes) {
            int color = type.getColor();
            red += (color >> 16) & 0xFF;
            green += (color >> 8) & 0xFF;
            blue += color & 0xFF;
        }

        int size = Math.max(1, visibleTypes.size());
        return ((red / size) << 16) | ((green / size) << 8) | (blue / size);
    }

    /**
     * 计算渲染时应该使用的本地包围盒。
     *
     * <p>这里目前主要处理床这种“两格一个任务点”的情况。
     * 其它普通单格方块则直接使用自身碰撞箱/轮廓箱。
     */
    private static @NotNull Box getCombinedLocalBox(
            @NotNull net.minecraft.world.BlockView world,
            @NotNull BlockPos pos,
            @NotNull BlockState state
    ) {
        VoxelShape shape = state.getCollisionShape(world, pos, ShapeContext.absent());
        if (shape.isEmpty()) {
            shape = state.getOutlineShape(world, pos, ShapeContext.absent());
        }

        Box baseBox = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();

        if (state.contains(BedBlock.PART) && state.contains(BedBlock.FACING)) {
            Direction facing = state.get(BedBlock.FACING);
            if (state.get(BedBlock.PART) == BedPart.FOOT) {
                return baseBox.expand(facing.getOffsetX(), 0, facing.getOffsetZ());
            }
            return baseBox.expand(-facing.getOffsetX(), 0, -facing.getOffsetZ());
        }

        /**
         * 小门是两格高，但下半门的轮廓箱本身只有一格高。
         * 任务点扫描记录的是下半门位置，因此这里手动把 AABB 扩展成完整两格，
         * 避免门透视时只看到下半截线框。
         */
        if (state.getBlock() instanceof SmallDoorBlock && state.contains(SmallDoorBlock.HALF)) {
            if (state.get(SmallDoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                return baseBox.expand(0, 1, 0);
            }
            return baseBox.offset(0, -1, 0).expand(0, 1, 0);
        }

        return baseBox;
    }

    /**
     * 读取玩家当前主手钥匙的第一行 lore，作为门匹配名。
     *
     * <p>门和钥匙的匹配规则本来就是：
     * 1. 主手是 KeyItem；
     * 2. lore 第一行字符串和门上的 keyName 完全相等；
     * 所以这里直接沿用原版 Wathe 的这套规则即可。
     */
    private static String getHeldKeyName(@NotNull ItemStack stack) {
        if (!stack.isOf(WatheItems.KEY)) {
            return null;
        }

        LoreComponent loreComponent = stack.get(DataComponentTypes.LORE);
        if (loreComponent == null || loreComponent.lines().isEmpty()) {
            return null;
        }

        return loreComponent.lines().getFirst().getString();
    }

    /**
     * 判断某个缓存门点是否和当前主手钥匙真正匹配。
     *
     * <p>这里客户端直接读取门方块实体同步过来的 keyName，
     * 不需要额外在任务点同步包里重复传一份门名。
     */
    private static boolean isMatchingHeldKeyDoor(@NotNull MinecraftClient client, @NotNull BlockPos pos, String heldKeyName) {
        if (heldKeyName == null || heldKeyName.isEmpty() || client.world == null) {
            return false;
        }

        return client.world.getBlockEntity(pos) instanceof SmallDoorBlockEntity doorBlockEntity
                && heldKeyName.equals(doorBlockEntity.getKeyName());
    }
}
