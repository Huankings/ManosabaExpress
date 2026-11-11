package dev.doctor4t.wathe.item;

import dev.doctor4t.wathe.api.door.DoorApi;
import dev.doctor4t.wathe.block_entity.DoorBlockEntity;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheSounds;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.AdventureUsable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

public class CrowbarItem extends Item implements AdventureUsable {
    public CrowbarItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        DoorBlockEntity door = DoorApi.findDoor(world, context.getBlockPos());
        if (door == null || DoorApi.isBlasted(door) || player == null) {
            return super.useOnBlock(context);
        }

        // 客户端只确认交互；门组状态、冷却和回放全部由服务端权威落地，避免预测状态与服务端竞态分叉。
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        DoorApi.OperationResult result = DoorApi.pryOpen(door);
        if (!result.applied()) {
            return ActionResult.PASS;
        }
        world.playSound(null, door.getPos(), WatheSounds.ITEM_CROWBAR_PRY, SoundCategory.BLOCKS, 2.5f, 1f);
        player.swingHand(Hand.MAIN_HAND, true);
        if (!player.isCreative()) {
            player.getItemCooldownManager().set(this, GameConstants.ITEM_COOLDOWNS.get(this));
        }

        if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            NbtCompound extra = new NbtCompound();
            extra.putString("mode", "pry");
            extra.putInt("affected_doors", result.affectedDoors());
            GameRecordManager.putBlockPos(extra, "pos", door.getPos());
            GameRecordManager.recordItemUse(serverPlayer, Registries.ITEM.getId(this), null, extra);
        }
        return ActionResult.CONSUME;
    }
}
