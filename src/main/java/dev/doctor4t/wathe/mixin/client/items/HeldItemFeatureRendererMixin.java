package dev.doctor4t.wathe.mixin.client.items;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.doctor4t.wathe.api.client.invisibility.HeldItemInvisibilityApi;
import dev.doctor4t.wathe.api.client.mood.PsychosisItemApi;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(HeldItemFeatureRenderer.class)
public class HeldItemFeatureRendererMixin {
    @WrapOperation(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getMainHandStack()Lnet/minecraft/item/ItemStack;"))
    public ItemStack wathe$hideMainHandItemsAndRenderPsychosisItems(LivingEntity instance, Operation<ItemStack> original) {
        ItemStack ret = original.call(instance);

        if (ret.isOf(WatheItems.NOTE)) {
            ret = ItemStack.EMPTY;
        }

        /*
         * 扩展职业注册的手持物不可见规则在这里统一生效。
         * 注意它放在低心情幻觉替换之前：如果玩家因为疯狂产生“看到对方手上有某个物品”的幻觉，
         * 后面的 psychosisItems 仍然能把 ItemStack.EMPTY 覆盖成幻觉物品，不会被隐藏逻辑吞掉。
         */
        ret = HeldItemInvisibilityApi.applyInvisibility(MinecraftClient.getInstance().player, instance, Hand.MAIN_HAND, ret);

        ret = PsychosisItemApi.resolveRenderStack(MinecraftClient.getInstance().player, instance, Hand.MAIN_HAND, ret);

        return ret;
    }

    @WrapOperation(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFFFFF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getOffHandStack()Lnet/minecraft/item/ItemStack;"))
    public ItemStack wathe$hideOffHandItems(LivingEntity instance, Operation<ItemStack> original) {
        ItemStack ret = original.call(instance);

        /*
         * 副手没有 Wathe 原生的幻觉替换逻辑，但扩展职业可能把专属物品放在副手。
         * 因此副手也走同一套公开 API，保证主手/副手规则表现一致。
         */
        ret = HeldItemInvisibilityApi.applyInvisibility(MinecraftClient.getInstance().player, instance, Hand.OFF_HAND, ret);
        return PsychosisItemApi.resolveRenderStack(MinecraftClient.getInstance().player, instance, Hand.OFF_HAND, ret);
    }
}
