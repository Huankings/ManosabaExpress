package dev.doctor4t.wathe.block;

import com.mojang.serialization.MapCodec;
import dev.doctor4t.wathe.api.tray.TrayEffectHandler;
import dev.doctor4t.wathe.api.tray.TrayEffectRegistry;
import dev.doctor4t.wathe.api.tray.TrayTakeRegistry;
import dev.doctor4t.wathe.api.tray.TrayTakeDecision;
import dev.doctor4t.wathe.block_entity.BeveragePlateBlockEntity;
import dev.doctor4t.wathe.index.WatheBlockEntities;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.index.WatheItems;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.TrayEffectUtils;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FoodPlatterBlock extends BlockWithEntity {
    public static final MapCodec<FoodPlatterBlock> CODEC = createCodec(FoodPlatterBlock::new);

    public FoodPlatterBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        BeveragePlateBlockEntity plate = new BeveragePlateBlockEntity(pos, state);
        plate.setDrink(false);
        return plate;
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    protected VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return this.getShape(state);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.getShape(state);
    }

    protected VoxelShape getShape(BlockState state) {
        return createCuboidShape(0, 0, 0, 16, 2, 16);
    }

    @Override
    protected ActionResult onUse(BlockState state, @NotNull World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(pos) instanceof BeveragePlateBlockEntity blockEntity)) return ActionResult.PASS;

        if (player.isCreative()) {
            ItemStack heldItem = player.getStackInHand(Hand.MAIN_HAND);
            if (!heldItem.isEmpty()) {
                blockEntity.addItem(heldItem);
                return ActionResult.SUCCESS;
            }
        }
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer
                && TrayEffectRegistry.tryApplyHeldEffect(serverPlayer, blockEntity, pos)) {
            return ActionResult.SUCCESS;
        }
        if (player.getStackInHand(Hand.MAIN_HAND).isOf(WatheItems.POISON_VIAL)
                && blockEntity.getPoisoner() == null
                && blockEntity.getTrayEffect() == null) {
            blockEntity.setPoisoner(player.getUuidAsString());
            player.getStackInHand(Hand.MAIN_HAND).decrement(1);
            player.playSoundToPlayer(SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.BLOCKS, 0.5f, 1f);
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                NbtCompound extra = new NbtCompound();
                GameRecordManager.putBlockPos(extra, "pos", pos);
                GameRecordManager.recordItemUse(serverPlayer, Registries.ITEM.getId(WatheItems.POISON_VIAL), null, extra);
            }
            return ActionResult.SUCCESS;
        }
        if (player.getStackInHand(Hand.MAIN_HAND).isEmpty()) {
            List<ItemStack> platter = blockEntity.getStoredItems();
            if (platter.isEmpty()) return ActionResult.SUCCESS;
            if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity trayPlayer)) return ActionResult.SUCCESS;

            List<ItemStack> eligibleItems = resolveEligibleItems(trayPlayer, blockEntity, platter, player);

            if (!eligibleItems.isEmpty()) {
                ItemStack randomItem = eligibleItems.get(world.random.nextInt(eligibleItems.size())).copy();
                randomItem.setCount(1);
                randomItem.set(DataComponentTypes.MAX_STACK_SIZE, 1);
                String poisoner = blockEntity.getPoisoner();
                String trayEffect = blockEntity.getTrayEffect();
                String trayEffectOwner = blockEntity.getTrayEffectOwner();
                if (poisoner != null) {
                    randomItem.set(WatheDataComponentTypes.POISONER, poisoner);
                    blockEntity.setPoisoner(null);
                }
                if (trayEffect != null) {
                    TrayEffectUtils.attachTrayEffect(randomItem, trayEffect, trayEffectOwner);
                    blockEntity.clearTrayEffect();
                }
                player.playSoundToPlayer(SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 1f, 1f);
                player.setStackInHand(Hand.MAIN_HAND, randomItem);
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                    NbtCompound extra = new NbtCompound();
                    extra.putString("item_name", net.minecraft.text.Text.Serialization.toJsonString(randomItem.getName(), serverPlayer.getRegistryManager()));
                    extra.putBoolean("is_drink_plate", blockEntity.isDrink());
                    if (trayEffect != null) {
                        extra.putString("tray_effect", trayEffect);
                        if (trayEffectOwner != null) {
                            try {
                                extra.putUuid("tray_effect_owner", UUID.fromString(trayEffectOwner));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        TrayEffectHandler effectHandler = net.minecraft.util.Identifier.tryParse(trayEffect) == null ? null : TrayEffectRegistry.getByEffectId(net.minecraft.util.Identifier.tryParse(trayEffect));
                        if (effectHandler != null) {
                            UUID owner = null;
                            if (trayEffectOwner != null) {
                                try {
                                    owner = UUID.fromString(trayEffectOwner);
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            effectHandler.onTakeFromTray(serverPlayer, randomItem, owner, extra);
                        }
                        Identifier parsedEffect = Identifier.tryParse(trayEffect);
                        if (parsedEffect != null) {
                            TrayEffectRegistry.appendReplayData(parsedEffect, extra);
                        }
                    }
                    if (poisoner != null) {
                        extra.putString("tray_effect_translation_key", "replay.effect.wathe.poison");
                        extra.putString("tray_effect_fallback", "Poison");
                    }
                    GameRecordManager.recordPlatterTake(serverPlayer, Registries.ITEM.getId(randomItem.getItem()), pos, poisoner, extra);
                }
            }
        }

        return ActionResult.PASS;
    }

    private static int countInventoryItems(PlayerEntity player, ItemStack target) {
        if (target == null || target.isEmpty()) return 0;
        int count = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.getItem() == target.getItem()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static List<ItemStack> resolveEligibleItems(
            net.minecraft.server.network.ServerPlayerEntity player,
            BeveragePlateBlockEntity plate,
            List<ItemStack> platter,
            PlayerEntity inventoryOwner
    ) {
        Map<String, List<ItemStack>> groups = new java.util.LinkedHashMap<>();
        Map<String, TrayTakeDecision> decisions = new java.util.HashMap<>();
        for (ItemStack candidate : platter) {
            TrayTakeDecision decision = TrayTakeRegistry.resolveGroupDecision(player, plate, candidate);
            if (decision == null) {
                // 没有扩展规则的物品共享一个默认组，恢复“一个托盘总共只能取一次”的原版语义。
                decision = new TrayTakeDecision("wathe:default", 1, TrayTakeDecision.Mode.DISTINCT_TYPES);
            }
            groups.computeIfAbsent(decision.groupId(), ignored -> new java.util.ArrayList<>()).add(candidate);
            decisions.putIfAbsent(decision.groupId(), decision);
        }

        List<ItemStack> result = new java.util.ArrayList<>();
        for (Map.Entry<String, List<ItemStack>> group : groups.entrySet()) {
            TrayTakeDecision decision = decisions.get(group.getKey());
            // 同一托盘可能存有多个相同 ItemStack；分组计算必须按物品类型去重。
            Map<String, ItemStack> uniqueCandidates = new java.util.LinkedHashMap<>();
            for (ItemStack candidate : group.getValue()) {
                uniqueCandidates.putIfAbsent(Registries.ITEM.getId(candidate.getItem()).toString(), candidate);
            }
            List<ItemStack> candidates = new java.util.ArrayList<>(uniqueCandidates.values());
            if (decision.mode() == TrayTakeDecision.Mode.TOTAL_COUNT) {
                int held = 0;
                for (ItemStack candidate : candidates) {
                    held += countInventoryItems(inventoryOwner, candidate);
                }
                if (held >= decision.limit()) {
                    continue;
                }
                // TOTAL_COUNT 规则仍只从当前数量未达到上限的候选类型中取。
                for (ItemStack candidate : candidates) {
                    if (countInventoryItems(inventoryOwner, candidate) < decision.limit()) {
                        result.add(candidate);
                    }
                }
            } else {
                // 当托盘提供的不同物品类型本来就少于职业上限时，不能再用“类型去重”限制玩家。
                // 例如只有一个熟猪排的托盘，厨师仍应能重复取到 3 份；此时改按该分组当前总数量计算。
                if (candidates.size() < decision.limit()) {
                    int heldCount = 0;
                    for (ItemStack candidate : candidates) {
                        heldCount += countInventoryItems(inventoryOwner, candidate);
                    }
                    if (heldCount < decision.limit()) {
                        result.addAll(candidates);
                    }
                    continue;
                }

                Set<String> heldTypes = new java.util.HashSet<>();
                for (ItemStack candidate : candidates) {
                    if (countInventoryItems(inventoryOwner, candidate) > 0) {
                        heldTypes.add(Registries.ITEM.getId(candidate.getItem()).toString());
                    }
                }
                if (heldTypes.size() >= decision.limit()) {
                    continue;
                }
                for (ItemStack candidate : candidates) {
                    if (!heldTypes.contains(Registries.ITEM.getId(candidate.getItem()).toString())) {
                        result.add(candidate);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull World world, BlockState state, BlockEntityType<T> type) {
        if (!world.isClient || !type.equals(WatheBlockEntities.BEVERAGE_PLATE)) return null;
        return BeveragePlateBlockEntity::clientTick;
    }
}
