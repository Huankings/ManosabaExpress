package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.block.SmallDoorBlock;
import dev.doctor4t.wathe.api.door.DoorApi;
import dev.doctor4t.wathe.block_entity.SmallDoorBlockEntity;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.AdventureUsable;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class LockpickItem extends Item implements AdventureUsable {
    public LockpickItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return super.useOnBlock(context);
        }
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof SmallDoorBlock) {
            BlockPos lowerPos = state.get(SmallDoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            if (world.getBlockEntity(lowerPos) instanceof SmallDoorBlockEntity entity) {
                if (player.isSneaking()) {
                    if (DoorApi.isBlasted(entity)) {
                        // 永久撬开的门优先级更高，开锁器不能把它重新写成“损坏但关闭”。
                        return ActionResult.PASS;
                    }
                    if (world.isClient) {
                        return ActionResult.SUCCESS;
                    }

                    DoorApi.OperationResult result = DoorApi.jamClosed(entity, GameConstants.JAMMED_DOOR_TIME);
                    if (!result.applied()) {
                        return ActionResult.PASS;
                    }
                    if (!player.isCreative()) {
                        player.getItemCooldownManager().set(this, GameConstants.ITEM_COOLDOWNS.get(this));
                    }

                    world.playSound(null, lowerPos.getX() + .5f, lowerPos.getY() + 1, lowerPos.getZ() + .5f, WatheSounds.ITEM_LOCKPICK_DOOR, SoundCategory.BLOCKS, 1f, 1f);

                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                        NbtCompound extra = new NbtCompound();
                        extra.putString("mode", "jam");
                        extra.putInt("affected_doors", result.affectedDoors());
                        GameRecordManager.putBlockPos(extra, "pos", lowerPos);
                        GameRecordManager.recordItemUse(serverPlayer, Registries.ITEM.getId(this), null, extra);
                    }
                    return ActionResult.CONSUME;
                }
            }

            return ActionResult.PASS;
        }

        return super.useOnBlock(context);
    }

}
