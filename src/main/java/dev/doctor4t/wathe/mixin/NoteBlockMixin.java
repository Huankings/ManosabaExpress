package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.cca.PlayerMoodComponent;
import net.minecraft.block.BlockState;
import net.minecraft.block.NoteBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoteBlock.class)
public class NoteBlockMixin {
    /**
     * 只有当右键音符盒的交互真正落到音符盒逻辑上时，才累计一次次数。
     * 这样可以避免客户端预测或被其它交互截胡时重复/误记。
     */
    @Inject(method = "onUse", at = @At("RETURN"))
    private void wathe$trackMusicTask(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer) || cir.getReturnValue() != ActionResult.CONSUME) {
            return;
        }

        PlayerMoodComponent.KEY.get(serverPlayer).playNoteBlock();
    }
}
