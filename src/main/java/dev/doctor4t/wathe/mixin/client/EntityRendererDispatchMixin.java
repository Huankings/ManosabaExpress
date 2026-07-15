package dev.doctor4t.wathe.mixin.client;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.api.client.appearance.PlayerAppearanceApi;
import dev.doctor4t.wathe.client.render.entity.PlayerBodyEntityRenderer;
import dev.doctor4t.wathe.entity.PlayerBodyEntity;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.entity.Entity;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@SuppressWarnings("unchecked")
@Mixin(EntityRenderDispatcher.class)
public class EntityRendererDispatchMixin {
    @Unique
    private static final Map<SkinTextures.Model, EntityRendererFactory<PlayerBodyEntity>> PLAYER_BODY_RENDERER_FACTORIES = Map.of(
            SkinTextures.Model.WIDE,
            context -> new PlayerBodyEntityRenderer<>(context, false),
            SkinTextures.Model.SLIM,
            context -> new PlayerBodyEntityRenderer<>(context, true)
    );

    @Unique
    private Map<SkinTextures.Model, EntityRenderer<? extends PlayerBodyEntity>> bodyModelRenderers = Map.of();

    @Inject(method = "reload", at = @At("TAIL"))
    public void reload(ResourceManager manager, CallbackInfo ci, @Local EntityRendererFactory.Context context) {
        this.bodyModelRenderers = reloadPlayerBodyRenderers(context);
    }

    @Inject(method = "getRenderer", at = @At("HEAD"), cancellable = true)
    public <T extends Entity> void wathe$addPlayerBodyRenderer(T entity, CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
        if (entity instanceof PlayerBodyEntity body) {
            /*
             * 尸体 renderer 的宽/细模型必须跟“最终显示皮肤”保持一致。
             * 这里不再只看真实死者 UUID，而是走 PlayerAppearanceApi：
             * - 服务端保存的 appearance UUID 会生效；
             * - 灵术师出窍这类只影响本地客户端的高优先级覆盖也会生效。
             */
            SkinTextures.Model model = PlayerAppearanceApi.resolveBodySkin(body).model();
            EntityRenderer<? extends PlayerBodyEntity> entityRenderer = this.bodyModelRenderers.get(model);
            cir.setReturnValue((EntityRenderer<? super T>) (entityRenderer != null ? entityRenderer : (EntityRenderer) this.bodyModelRenderers.get(SkinTextures.Model.WIDE)));
        }
    }


    @Unique
    private static Map<SkinTextures.Model, EntityRenderer<? extends PlayerBodyEntity>> reloadPlayerBodyRenderers(EntityRendererFactory.Context ctx) {
        ImmutableMap.Builder<SkinTextures.Model, EntityRenderer<? extends PlayerBodyEntity>> builder = ImmutableMap.builder();
        PLAYER_BODY_RENDERER_FACTORIES.forEach((model, factory) -> {
            try {
                builder.put(model, factory.create(ctx));
            } catch (Exception var5) {
                throw new IllegalArgumentException("Failed to create player body model for " + model, var5);
            }
        });
        return builder.build();
    }

}
