package dev.doctor4t.wathe.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import dev.doctor4t.wathe.cca.PlayerPoisonComponent;
import dev.doctor4t.wathe.game.GameConstants;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.record.GameRecordManager;
import dev.doctor4t.wathe.util.AdventureUsable;
import dev.doctor4t.wathe.util.TrayEffectUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Shadow
    public abstract Item getItem();

    @WrapOperation(method = "useOnBlock", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/PlayerAbilities;allowModifyWorld:Z"))
    public boolean useOnBlock(PlayerAbilities instance, Operation<Boolean> original) {
        if (this.getItem() instanceof AdventureUsable) return true;
        return original.call(instance);
    }

    /**
     * 给喝药水任务再加一层兜底。
     * 即便 PotionItem 的注入点因为版本细节没有触发，这里也能在真正 finishUsing 时补上检测。
     */
    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void wathe$completePotionTask(World world, LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer && this.getItem() == Items.POTION) {
            ItemStack replaySnapshot = ((ItemStack) (Object) this).copy();
            PlayerMoodComponent.KEY.get(serverPlayer).drinkPotion();
            if (!TrayEffectUtils.handleConsumeEffect(serverPlayer, replaySnapshot, "drink_potion")) {
                String poisoner = replaySnapshot.getOrDefault(WatheDataComponentTypes.POISONER, null);
                GameRecordManager.recordConsumeItem(
                        serverPlayer,
                        replaySnapshot,
                        "drink_potion",
                        poisoner != null,
                        poisoner == null ? null : java.util.UUID.fromString(poisoner),
                        null
                );

                /**
                 * 原版 wathe 的毒托盘主要走食物 / 鸡尾酒链路，药水这边只有回放没有真正施毒。
                 * 现在扩展试剂也需要支持“下在药水里”，这里顺手把药水型托盘毒药补成完整逻辑，
                 * 避免出现“回放显示喝了带毒药水，但实际上不会中毒”的错位问题。
                 */
                if (poisoner != null) {
                    PlayerPoisonComponent poisonComponent = PlayerPoisonComponent.KEY.get(serverPlayer);
                    int poisonTicks = poisonComponent.poisonTicks;
                    NbtCompound poisonData = new NbtCompound();
                    poisonData.putString("item", net.minecraft.registry.Registries.ITEM.getId(replaySnapshot.getItem()).toString());
                    poisonData.putString("item_name", net.minecraft.text.Text.Serialization.toJsonString(replaySnapshot.getName(), serverPlayer.getRegistryManager()));
                    if (poisonTicks == -1) {
                        poisonComponent.setDetailedPoisonTicks(
                                world.getRandom().nextBetween(PlayerPoisonComponent.clampTime.getLeft(), PlayerPoisonComponent.clampTime.getRight()),
                                java.util.UUID.fromString(poisoner),
                                GameConstants.DeathReasons.POISON,
                                poisonData
                        );
                    } else {
                        poisonComponent.setDetailedPoisonTicks(
                                MathHelper.clamp(poisonTicks - world.getRandom().nextBetween(100, 300), 0, PlayerPoisonComponent.clampTime.getRight()),
                                java.util.UUID.fromString(poisoner),
                                GameConstants.DeathReasons.POISON,
                                poisonData
                        );
                    }
                }
            }
        }
    }
}
