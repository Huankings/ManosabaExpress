package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.doctor4t.wathe.api.door.DoorApi;
import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.block_entity.SmallDoorBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class UpdateDoorsCommand {
    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:updateDoors").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
            ServerCommandSource source = context.getSource();

            BlockPos playerPos = source.getPlayer().getBlockPos();
            BlockPos.Mutable mutable = new BlockPos.Mutable();
            Set<BlockPos> updatedDoors = new HashSet<>();
            for (int x = -250; x <= 250; x++) {
                for (int y = -10; y <= 10; y++) {
                    for (int z = -250; z <= 250; z++) {
                        mutable.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                        ServerWorld world = source.getWorld();
                        BlockState blockState = world.getBlockState(mutable);
                        if (blockState.getBlock() instanceof SmallDoorBlock && blockState.get(SmallDoorBlock.HALF).equals(DoubleBlockHalf.LOWER)) {
                            if (world.getBlockEntity(mutable) instanceof SmallDoorBlockEntity entity) {
                                /*
                                 * 双门有两个下半部分实体。移除旧的同 tick 门锁后，指令必须显式按门组去重，
                                 * 否则循环扫到第二侧时会把刚打开的整组再次关闭。
                                 */
                                Set<BlockPos> groupPositions = new HashSet<>();
                                DoorApi.getGroupMembers(entity)
                                        .forEach(member -> groupPositions.add(member.getPos().toImmutable()));
                                if (groupPositions.stream().anyMatch(updatedDoors::contains)) {
                                    continue;
                                }
                                SmallDoorBlock.toggleDoor(blockState, world, entity, mutable);
                                updatedDoors.addAll(groupPositions);
                            }
                        }
                    }
                }
            }

            return 1;
        }));
    }
}
