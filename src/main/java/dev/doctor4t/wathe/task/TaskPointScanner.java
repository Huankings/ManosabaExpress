package dev.doctor4t.wathe.task;

import dev.doctor4t.wathe.block.MountableBlock;
import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import dev.doctor4t.wathe.block_entity.SmallDoorBlockEntity;
import dev.doctor4t.wathe.cca.MapVariablesWorldComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.item.CocktailItem;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端任务点扫描器。
 *
 * <p>扫描思路不是直接暴力扫整个世界，而是严格参考当前地图复制区域：
 * 1. 先根据 resetTemplateArea + resetPasteOffset 算出当前列车实际被复制到哪里；
 * 2. 只在这一块范围内枚举方块，避免扫到大厅或别的杂项区域；
 * 3. 再额外要求任务点必须落在 playArea 内，满足“只能透视游戏区域内任务点”的需求。
 */
public final class TaskPointScanner {

    private TaskPointScanner() {
    }

    /**
     * 扫描当前世界里的所有任务点。
     */
    public static @NotNull Map<BlockPos, EnumSet<TaskPointType>> scan(@NotNull ServerWorld world) {
        MapVariablesWorldComponent areas = MapVariablesWorldComponent.KEY.get(world);
        BlockBox scanArea = getCurrentTrainScanArea(areas);
        HashMap<BlockPos, EnumSet<TaskPointType>> taskPoints = new HashMap<>();
        HashSet<BlockPos> waterBlocks = new HashSet<>();

        /**
         * “某种物品是不是可烤生食”只和物品种类有关，与单个 ItemStack 的数量无关，
         * 因此在一次扫描过程中做一个小缓存，避免反复查配方。
         */
        HashMap<Item, Boolean> cookableFoodCache = new HashMap<>();

        for (int x = scanArea.getMinX(); x <= scanArea.getMaxX(); x++) {
            for (int y = scanArea.getMinY(); y <= scanArea.getMaxY(); y++) {
                for (int z = scanArea.getMinZ(); z <= scanArea.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    // 只保留游戏区域里的点，防止大厅或模板区的同类方块被错记进去。
                    if (!areas.getPlayArea().contains(pos.toCenterPos())) {
                        continue;
                    }

                    BlockState state = world.getBlockState(pos);
                    scanBlockTaskPoints(world, pos, state, taskPoints);
                    if (isWaterTaskPointCandidate(state)) {
                        waterBlocks.add(pos.toImmutable());
                    }

                    BlockEntity blockEntity = world.getBlockEntity(pos);
                    if (blockEntity instanceof SmallDoorBlockEntity smallDoorBlockEntity && !smallDoorBlockEntity.getKeyName().isEmpty()) {
                        /**
                         * 这里把“已经绑定了钥匙名的门”也记录进任务点缓存。
                         *
                         * <p>注意它和普通任务点不同：
                         * 1. 不依赖任务刷新；
                         * 2. 客户端最终是否显示，要看玩家当前主手钥匙的 lore 是否和门上的 keyName 对应。
                         *
                         * <p>之所以服务端先把这些门坐标记录下来，是为了避免客户端每帧自己扫整张地图找门。
                         * 客户端只需要在现有缓存上做一次“当前手持钥匙能不能对应上这扇门”的轻量过滤即可。
                         */
                        addTaskPoint(taskPoints, pos, TaskPointType.KEYED_DOOR);
                    }
                    if (blockEntity instanceof BeveragePlateBlockEntity plateBlockEntity) {
                        scanPlateTaskPoints(world, pos, plateBlockEntity, cookableFoodCache, taskPoints);
                    }
                }
            }
        }

        scanWaterTaskPoints(waterBlocks, taskPoints);
        return taskPoints;
    }

    /**
     * 计算当前真实地图的扫描范围。
     *
     * <p>这里完全沿用地图重置逻辑的“模板区域 + 粘贴偏移”思路，
     * 保证任务点记录和实际被复制出来的列车区域保持一致。
     */
    private static @NotNull BlockBox getCurrentTrainScanArea(@NotNull MapVariablesWorldComponent areas) {
        BlockPos templateMin = BlockPos.ofFloored(areas.getResetTemplateArea().getMinPos());
        BlockPos templateMax = BlockPos.ofFloored(areas.getResetTemplateArea().getMaxPos());
        BlockBox templateBox = BlockBox.create(templateMin, templateMax);

        BlockPos pastedMin = templateMin.add(areas.getResetPasteOffset());
        BlockPos pastedMax = pastedMin.add(templateBox.getDimensions());
        return BlockBox.create(pastedMin, pastedMax);
    }

    /**
     * 扫描“方块本身就能确定”的任务点类型。
     */
    private static void scanBlockTaskPoints(
            @NotNull ServerWorld world,
            @NotNull BlockPos pos,
            @NotNull BlockState state,
            @NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints
    ) {
        if (state.isIn(BlockTags.BEDS)) {
            addTaskPoint(taskPoints, getCanonicalBedPos(pos, state), TaskPointType.BED);
        }

        boolean isLitCampfire = state.isOf(Blocks.CAMPFIRE)
                && state.contains(CampfireBlock.LIT)
                && state.get(CampfireBlock.LIT);
        if (state.isOf(Blocks.FIRE) || isLitCampfire) {
            addTaskPoint(taskPoints, pos, TaskPointType.FIRE_SOURCE);
        }

        if (state.getBlock() instanceof MountableBlock) {
            addTaskPoint(taskPoints, pos, TaskPointType.SEAT);
        }

        if (state.isOf(Blocks.NOTE_BLOCK)) {
            addTaskPoint(taskPoints, pos, TaskPointType.NOTE_BLOCK);
        }

        if (state.isOf(Blocks.LECTERN)
                && state.contains(LecternBlock.HAS_BOOK)
                && state.get(LecternBlock.HAS_BOOK)) {
            addTaskPoint(taskPoints, pos, TaskPointType.LECTERN);
        }

        if (state.isOf(Blocks.FURNACE)) {
            addTaskPoint(taskPoints, pos, TaskPointType.FURNACE);
        }

        if (state.isOf(Blocks.SMOKER)) {
            addTaskPoint(taskPoints, pos, TaskPointType.SMOKER);
        }
    }

    /**
     * 扫描“泡水”任务点。
     *
     * <p>这里不是简单地把所有水方块都记成透视点，而是按“连通水域”整体判断：
     * 1. 先把相连的水方块做一次 BFS 分组；
     * 2. 统计这一整块水域的连通格数；
     * 3. 统计这一整块水域的纵向高度（maxY - minY + 1）；
     * 4. 只有同时满足限制时，才把这整块里的每个水方块都记成透视点。
     *
     * <p>这样可以避免河流、大池塘之类的大面积连通水域被整块高亮。
     */
    private static void scanWaterTaskPoints(
            @NotNull HashSet<BlockPos> waterBlocks,
            @NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints
    ) {
        if (waterBlocks.isEmpty()) {
            return;
        }

        int maxHeight = GameConstants.WATER_TASK_POINT_MAX_HEIGHT;
        int maxConnectedBlocks = GameConstants.WATER_TASK_POINT_MAX_CONNECTED_BLOCKS;

        /**
         * 如果两个限制都被设成 -1，就表示完全取消限制：
         * 游戏区域内扫描到的所有水方块都直接作为透视点。
         */
        if (maxHeight == -1 && maxConnectedBlocks == -1) {
            for (BlockPos pos : waterBlocks) {
                addTaskPoint(taskPoints, pos, TaskPointType.WATER_SOURCE);
            }
            return;
        }

        HashSet<BlockPos> visited = new HashSet<>();
        for (BlockPos startPos : waterBlocks) {
            if (!visited.add(startPos)) {
                continue;
            }

            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            ArrayList<BlockPos> connectedWaterRegion = new ArrayList<>();
            queue.add(startPos);

            int minY = startPos.getY();
            int maxY = startPos.getY();

            while (!queue.isEmpty()) {
                BlockPos currentPos = queue.removeFirst();
                connectedWaterRegion.add(currentPos);
                minY = Math.min(minY, currentPos.getY());
                maxY = Math.max(maxY, currentPos.getY());

                for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
                    BlockPos nextPos = currentPos.offset(direction);
                    if (!waterBlocks.contains(nextPos) || !visited.add(nextPos)) {
                        continue;
                    }
                    queue.addLast(nextPos);
                }
            }

            int connectedBlockCount = connectedWaterRegion.size();
            int waterHeight = maxY - minY + 1;

            boolean passesHeightLimit = maxHeight < 0 || waterHeight <= maxHeight;
            boolean passesAreaLimit = maxConnectedBlocks < 0 || connectedBlockCount <= maxConnectedBlocks;
            if (!passesHeightLimit || !passesAreaLimit) {
                continue;
            }

            for (BlockPos regionPos : connectedWaterRegion) {
                addTaskPoint(taskPoints, regionPos, TaskPointType.WATER_SOURCE);
            }
        }
    }

    /**
     * 扫描托盘里的物品内容，把“同一个托盘具备哪些任务点用途”一起记下来。
     */
    private static void scanPlateTaskPoints(
            @NotNull ServerWorld world,
            @NotNull BlockPos pos,
            @NotNull BeveragePlateBlockEntity plateBlockEntity,
            @NotNull Map<Item, Boolean> cookableFoodCache,
            @NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints
    ) {
        boolean hasFood = false;
        boolean hasCocktail = false;
        boolean hasPotion = false;
        boolean hasFishingRod = false;
        boolean hasCookableRawFood = false;
        boolean hasFuel = false;

        for (ItemStack stack : plateBlockEntity.getStoredItems()) {
            if (stack.isEmpty()) {
                continue;
            }

            /**
             * “去吃点零食”任务只认真正的食物，不把 wathe 自己的鸡尾酒算进去。
             *
             * <p>这里需要额外排除 {@link CocktailItem}，原因是这些鸡尾酒在物品注册时
             * 复用了原版蜂蜜瓶的 food 组件，因此如果只看 FOOD 组件，
             * 就会把“纯鸡尾酒托盘”错误透视成“零食托盘”。
             *
             * <p>修复后规则变成：
             * 1. 有 FOOD 组件；
             * 2. 但物品本身不是 CocktailItem；
             * 满足这两个条件才算“食物托盘”。
             */
            if (stack.get(DataComponentTypes.FOOD) != null && !(stack.getItem() instanceof CocktailItem)) {
                hasFood = true;
            }

            if (stack.getItem() instanceof CocktailItem) {
                hasCocktail = true;
            }

            if (stack.isOf(Items.POTION)) {
                hasPotion = true;
            }

            if (stack.isOf(Items.FISHING_ROD)) {
                hasFishingRod = true;
            }

            if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)) {
                hasFuel = true;
            }

            if (isCookableFood(world, stack, cookableFoodCache)) {
                hasCookableRawFood = true;
            }
        }

        if (hasFood) {
            addTaskPoint(taskPoints, pos, TaskPointType.FOOD_TRAY);
        }
        if (hasCocktail) {
            addTaskPoint(taskPoints, pos, TaskPointType.COCKTAIL_TRAY);
        }
        if (hasPotion) {
            addTaskPoint(taskPoints, pos, TaskPointType.POTION_TRAY);
        }
        if (hasFishingRod) {
            addTaskPoint(taskPoints, pos, TaskPointType.FISHING_ROD_TRAY);
        }
        if (hasCookableRawFood) {
            addTaskPoint(taskPoints, pos, TaskPointType.RAW_FOOD_TRAY);
        }
        if (hasFuel) {
            addTaskPoint(taskPoints, pos, TaskPointType.FUEL_TRAY);
        }
    }

    /**
     * 规范化床位坐标，统一记录在床脚一端，避免同一张床因为头/脚两格被记成两个任务点。
     */
    private static @NotNull BlockPos getCanonicalBedPos(@NotNull BlockPos pos, @NotNull BlockState state) {
        if (state.contains(BedBlock.PART) && state.contains(BedBlock.FACING) && state.get(BedBlock.PART) == net.minecraft.block.enums.BedPart.HEAD) {
            return pos.offset(state.get(BedBlock.FACING).getOpposite());
        }
        return pos;
    }

    /**
     * 判断某个方块是否应该被视为“泡水任务点”的候选水方块。
     *
     * <p>这里不再只认 {@link Blocks#WATER} 本体，而是改成“只要当前位置存在水流体状态就算”。
     * 这样做是为了兼容两类实际场景：
     * 1. 水里有海草、海带等水生植物时，这些格子本身不是纯水方块，但内部仍然有水流体；
     * 2. 浴室里常见的含水台阶、含水半砖、含水楼梯，同样不是纯水方块，但玩家依然是在水里。
     *
     * <p>改成看 fluid state 以后，这些“非完整水方块”就会和周围水格一起参与连通计算，
     * 不会再把它们错误地当成断点，从而避免：
     * 1. 河边被海草隔断后，误把中间的小块完整水域单独透视出来；
     * 2. 浴室里的含水半砖区域明明能泡水，却完全不被透视。
     */
    private static boolean isWaterTaskPointCandidate(@NotNull BlockState state) {
        return state.getFluidState().isIn(FluidTags.WATER);
    }

    /**
     * 判断某个物品是否能通过熔炉或烟熏炉加工成“可食用成品”。
     *
     * <p>这比简单硬编码食材列表更稳：
     * 以后如果地图里换了别的可烤食物，只要原版配方支持，这里就能自动识别。
     */
    private static boolean isCookableFood(
            @NotNull ServerWorld world,
            @NotNull ItemStack stack,
            @NotNull Map<Item, Boolean> cookableFoodCache
    ) {
        Item item = stack.getItem();
        if (cookableFoodCache.containsKey(item)) {
            return cookableFoodCache.get(item);
        }

        boolean isCookableFood = hasFoodCookingRecipe(world, stack, RecipeType.SMELTING)
                || hasFoodCookingRecipe(world, stack, RecipeType.SMOKING);
        cookableFoodCache.put(item, isCookableFood);
        return isCookableFood;
    }

    /**
     * 查询指定烹饪配方类型是否存在，并且产物本身也是食物。
     */
    private static boolean hasFoodCookingRecipe(
            @NotNull ServerWorld world,
            @NotNull ItemStack stack,
            @NotNull RecipeType<? extends Recipe<SingleStackRecipeInput>> recipeType
    ) {
        SingleStackRecipeInput recipeInput = new SingleStackRecipeInput(stack);
        Optional<? extends RecipeEntry<? extends Recipe<SingleStackRecipeInput>>> recipe =
                world.getRecipeManager().getFirstMatch(recipeType, recipeInput, world);
        if (recipe.isEmpty()) {
            return false;
        }

        ItemStack output = recipe.get().value().getResult(world.getRegistryManager());
        return !output.isEmpty() && output.get(DataComponentTypes.FOOD) != null;
    }

    /**
     * 往结果表里追加一个任务点类型。
     *
     * <p>如果同一坐标已经存在别的用途，就把类型并进去，而不是覆盖掉原来的类型。
     */
    private static void addTaskPoint(
            @NotNull Map<BlockPos, EnumSet<TaskPointType>> taskPoints,
            @NotNull BlockPos pos,
            @NotNull TaskPointType type
    ) {
        taskPoints.computeIfAbsent(pos.toImmutable(), ignored -> EnumSet.noneOf(TaskPointType.class)).add(type);
    }
}
