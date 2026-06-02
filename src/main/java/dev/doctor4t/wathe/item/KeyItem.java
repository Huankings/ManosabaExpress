package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.block_entity.SmallDoorBlockEntity;
import dev.doctor4t.wathe.task.TaskPointSyncManager;
import dev.doctor4t.wathe.util.AdventureUsable;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class KeyItem extends Item implements AdventureUsable {
    public KeyItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof SmallDoorBlock) {
            BlockPos lowerPos = state.get(SmallDoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            if (world.getBlockEntity(lowerPos) instanceof SmallDoorBlockEntity entity) {
                ItemStack mainHandStack = player.getMainHandStack();
                LoreComponent loreComponent = mainHandStack.get(DataComponentTypes.LORE);
                if (loreComponent != null) {
                    List<Text> lines = loreComponent.lines();
                    if (lines == null || lines.isEmpty()) {
                        return ActionResult.PASS;
                    }

                    // Sneaking creative player with key sets the door to require a key with the same name
                    if (player.isCreative() && player.isSneaking()) {
                        String roomName = lines.getFirst().getString();
                        entity.setKeyName(roomName);
                        /**
                         * 立刻同步门方块实体，确保客户端能马上拿到新的 keyName。
                         *
                         * <p>这对任务点透视里的“手持钥匙高亮对应门”尤其重要：
                         * 如果这里只改服务端字段而不发更新包，客户端在区块重载前仍然看不到新门名，
                         * 透视就没法第一时间匹配到这扇门。
                         */
                        entity.sync();
                        /**
                         * 同时重扫一次任务点缓存。
                         *
                         * <p>因为“钥匙门透视”走的是任务点缓存里的门坐标，
                         * 如果一扇门是刚刚被赋予 keyName 的，它在旧缓存里原本并不存在。
                         * 这里立刻重扫并广播，可以让这扇门马上进入透视系统，
                         * 不需要管理员额外手动执行一次 /wathe:taskPoints reload。
                         */
                        if (world instanceof ServerWorld serverWorld) {
                            TaskPointSyncManager.reloadAndBroadcast(serverWorld);
                        }
                        return ActionResult.SUCCESS;
                    }
                }
            }

            return ActionResult.PASS;
        }
        return super.useOnBlock(context);
    }
}
