package dev.doctor4t.wathe.block_entity;

import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.index.WatheBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;

public class SmallDoorBlockEntity extends DoorBlockEntity {

    public SmallDoorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static SmallDoorBlockEntity createGlass(BlockPos pos, BlockState state) {
        return new SmallDoorBlockEntity(WatheBlockEntities.SMALL_GLASS_DOOR, pos, state);
    }

    public static SmallDoorBlockEntity createWood(BlockPos pos, BlockState state) {
        return new SmallDoorBlockEntity(WatheBlockEntities.SMALL_WOOD_DOOR, pos, state);
    }

    @Override
    protected void toggleBlocks() {
        if (this.world == null) {
            return;
        }
        this.world.setBlockState(this.pos, this.getCachedState().with(SmallDoorBlock.OPEN, this.open), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        this.world.setBlockState(this.pos.up(), this.getCachedState().with(SmallDoorBlock.OPEN, this.open).with(SmallDoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }
}
