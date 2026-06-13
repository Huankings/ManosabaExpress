package dev.doctor4t.wathe.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.compat.SodiumShaderInterface;
import dev.doctor4t.wathe.compat.sodium.SceneryRenderSection;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.BitwiseMath;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(value = DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin {
    @Unique
    private static final int MODEL_UNASSIGNED = ModelQuadFacing.UNASSIGNED.ordinal();
    @Unique
    private static final int MODEL_POS_X = ModelQuadFacing.POS_X.ordinal();
    @Unique
    private static final int MODEL_POS_Y = ModelQuadFacing.POS_Y.ordinal();
    @Unique
    private static final int MODEL_POS_Z = ModelQuadFacing.POS_Z.ordinal();
    @Unique
    private static final int MODEL_NEG_X = ModelQuadFacing.NEG_X.ordinal();
    @Unique
    private static final int MODEL_NEG_Y = ModelQuadFacing.NEG_Y.ordinal();
    @Unique
    private static final int MODEL_NEG_Z = ModelQuadFacing.NEG_Z.ordinal();

    @Unique
    private static ByteBuffer wathe$sectionOffsetBuffer = MemoryUtil.memAlloc(RenderRegion.REGION_SIZE * 16);
    @Unique
    private static GlMutableBuffer wathe$sectionOffsetGlBuffer;

    @WrapOperation(
            method = "fillCommandBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;getVisibleFaces(IIIIII)I"
            ),
            remap = false
    )
    private static int wathe$getVisibleFaces(
            int originX,
            int originY,
            int originZ,
            int chunkX,
            int chunkY,
            int chunkZ,
            Operation<Integer> original,
            @Local(name = "sectionIndex") int sectionIndex
    ) {
        wathe$ensureSectionOffsetBuffer();

        if (WatheClient.isTrainMoving()) {
            SceneryRenderSection section = SceneryRenderSection.cache.get(ChunkSectionPos.asLong(chunkX, chunkY, chunkZ));

            if (section != null) {
                float boundsMinX = section.getVirtualOriginX();
                float boundsMaxX = boundsMinX + 16.0f;
                int boundsMinY = section.getVirtualOriginY();
                int boundsMaxY = boundsMinY + 16;
                int boundsMinZ = section.getVirtualOriginZ();
                int boundsMaxZ = boundsMinZ + 16;

                return wathe$getVisibleFacesForVirtualBounds(originX, originY, originZ,
                        boundsMinX, boundsMaxX, boundsMinY, boundsMaxY, boundsMinZ, boundsMaxZ);
            }
        }

        wathe$writeSectionOffset(sectionIndex, 0.0f, 0.0f, 0.0f);
        return original.call(originX, originY, originZ, chunkX, chunkY, chunkZ);
    }

    @Inject(method = "fillCommandBuffer",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataUnsafe;getSliceMask(J)I"),
            remap = false)
    private static void wathe$writeSceneryOffset(
            MultiDrawBatch batch,
            RenderRegion region,
            SectionRenderDataStorage renderDataStorage,
            ChunkRenderList renderList,
            CameraTransform camera,
            TerrainRenderPass pass,
            boolean useBlockFaceCulling,
            CallbackInfo ci,
            @Local(name = "sectionIndex") int sectionIndex,
            @Local(name = "chunkX") int chunkX,
            @Local(name = "chunkY") int chunkY,
            @Local(name = "chunkZ") int chunkZ
    ) {
        wathe$ensureSectionOffsetBuffer();

        if (WatheClient.isTrainMoving()) {
            SceneryRenderSection section = SceneryRenderSection.cache.get(ChunkSectionPos.asLong(chunkX, chunkY, chunkZ));
            if (section != null) {
                // 这里写入的 offset 会被 shader 按 draw_id 读取，真正把风景 section 移到虚拟位置。
                wathe$writeSectionOffset(sectionIndex, section.getOffsetX(), section.getOffsetY(), section.getOffsetZ());
                return;
            }
        }

        wathe$writeSectionOffset(sectionIndex, 0.0f, 0.0f, 0.0f);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;executeDrawBatch(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlTessellation;Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;)V"),
            remap = false)
    private void wathe$modifyChunkRenderBefore(
            ChunkRenderMatrices matrices,
            CommandList commandList,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            CallbackInfo ci,
            @Local(ordinal = 0) ChunkShaderInterface shader
    ) {
        wathe$ensureSectionOffsetBuffer();

        wathe$sectionOffsetGlBuffer = commandList.createMutableBuffer();
        if (wathe$sectionOffsetGlBuffer == null) {
            return;
        }

        commandList.uploadData(wathe$sectionOffsetGlBuffer, wathe$sectionOffsetBuffer, GlBufferUsage.STREAM_DRAW);
        ((SodiumShaderInterface) shader).wathe$set(wathe$sectionOffsetGlBuffer);
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;executeDrawBatch(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlTessellation;Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;)V",
            shift = At.Shift.AFTER),
            remap = false)
    private void wathe$modifyChunkRenderAfter(
            ChunkRenderMatrices matrices,
            CommandList commandList,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            CallbackInfo ci
    ) {
        if (wathe$sectionOffsetBuffer != null) {
            MemoryUtil.memFree(wathe$sectionOffsetBuffer);
            wathe$sectionOffsetBuffer = null;
        }

        if (wathe$sectionOffsetGlBuffer != null) {
            commandList.deleteBuffer(wathe$sectionOffsetGlBuffer);
            wathe$sectionOffsetGlBuffer = null;
        }
    }

    @Unique
    private static void wathe$ensureSectionOffsetBuffer() {
        if (wathe$sectionOffsetBuffer == null) {
            wathe$sectionOffsetBuffer = MemoryUtil.memAlloc(RenderRegion.REGION_SIZE * 16);
        }
    }

    @Unique
    private static void wathe$writeSectionOffset(int sectionIndex, float x, float y, float z) {
        int offset = sectionIndex * 16;
        wathe$sectionOffsetBuffer.putFloat(offset, x);
        wathe$sectionOffsetBuffer.putFloat(offset + 4, y);
        wathe$sectionOffsetBuffer.putFloat(offset + 8, z);
        wathe$sectionOffsetBuffer.putFloat(offset + 12, 0.0f);
    }

    @Unique
    private static int wathe$getVisibleFacesForVirtualBounds(
            int originX,
            int originY,
            int originZ,
            float boundsMinX,
            float boundsMaxX,
            int boundsMinY,
            int boundsMaxY,
            int boundsMinZ,
            int boundsMaxZ
    ) {
        int planes = 1 << MODEL_UNASSIGNED;

        // Sodium 原版按 section 原坐标判断哪些面要画；风景移动后必须改用虚拟包围盒判断。
        planes |= (originX > boundsMinX - 3.0f ? 1 : 0) << MODEL_POS_X;
        planes |= BitwiseMath.greaterThan(originY, boundsMinY - 3) << MODEL_POS_Y;
        planes |= BitwiseMath.greaterThan(originZ, boundsMinZ - 3) << MODEL_POS_Z;

        planes |= (originX < boundsMaxX + 3.0f ? 1 : 0) << MODEL_NEG_X;
        planes |= BitwiseMath.lessThan(originY, boundsMaxY + 3) << MODEL_NEG_Y;
        planes |= BitwiseMath.lessThan(originZ, boundsMaxZ + 3) << MODEL_NEG_Z;

        return planes;
    }
}
