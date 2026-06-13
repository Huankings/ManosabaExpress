package dev.doctor4t.wathe.mixin.compat.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.compat.sodium.SceneryOcclusionVisitor;
import dev.doctor4t.wathe.compat.sodium.SceneryRenderSection;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.SceneryConfig;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMaps;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
    @Unique
    private static final float CHUNK_SECTION_RADIUS = 8.0f;
    @Unique
    private static final float CHUNK_SECTION_SIZE = CHUNK_SECTION_RADIUS + 1.0f + 0.125f;

    @Unique
    private final Long2ReferenceMap<SceneryRenderSection> wathe$scenerySectionsByOriginPosition = new Long2ReferenceOpenHashMap<>();
    @Unique
    private final Long2ReferenceMap<RenderSection> wathe$trainSectionsByPosition = new Long2ReferenceOpenHashMap<>();
    @Unique
    private OcclusionCuller wathe$trainOcclusionCuller;

    @Unique
    private int wathe$lastTileWidth;
    @Unique
    private int wathe$lastTileLength;
    @Unique
    private int wathe$lastHeight;
    @Unique
    private float wathe$lastCameraX;
    @Unique
    private float wathe$lastMotionOffset;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wathe$onConstructTail(ClientWorld level, int renderDistance, CommandList commandList, CallbackInfo ci) {
        // 新建 RenderSectionManager 时清空静态 shader offset 缓存，避免换世界后保留旧 section。
        SceneryRenderSection.cache.clear();
        this.wathe$trainOcclusionCuller = new OcclusionCuller(Long2ReferenceMaps.unmodifiable(this.wathe$trainSectionsByPosition), level);
    }

    @Inject(method = "onSectionAdded", at = @At("TAIL"))
    private void wathe$onSectionAdded(int x, int y, int z, CallbackInfo ci, @Local long key, @Local RenderSection renderSection) {
        if (renderSection == null) {
            return;
        }

        // 列车车厢主体在高处，保留给 Sodium 原生 occlusion culler 单独处理。
        if (renderSection.getOriginY() >= 64) {
            this.wathe$trainSectionsByPosition.put(key, renderSection);
            return;
        }

        SceneryConfig sceneryConfig = wathe$getSceneryConfig();
        if (renderSection.getOriginX() + 16 <= sceneryConfig.minX()
                || renderSection.getOriginX() >= sceneryConfig.maxX()
                || renderSection.getOriginZ() + 16 <= sceneryConfig.minZ()
                || renderSection.getOriginZ() >= sceneryConfig.maxZ()) {
            return;
        }

        // 只把配置范围内的低处风景 section 放进虚拟坐标缓存，减少每帧遍历量。
        SceneryRenderSection sceneryRenderSection = new SceneryRenderSection(renderSection);
        this.wathe$scenerySectionsByOriginPosition.put(key, sceneryRenderSection);
        SceneryRenderSection.cache.put(key, sceneryRenderSection);
    }

    @Inject(method = "onSectionRemoved", at = @At("TAIL"))
    private void wathe$onSectionRemoved(int x, int y, int z, CallbackInfo ci, @Local long sectionPos) {
        this.wathe$trainSectionsByPosition.remove(sectionPos);
        this.wathe$scenerySectionsByOriginPosition.remove(sectionPos);
        SceneryRenderSection.cache.remove(sectionPos);
    }

    @WrapOperation(
            method = "createTerrainRenderList",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller$Visitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"
            )
    )
    private void wathe$findVisible(
            OcclusionCuller instance,
            OcclusionCuller.Visitor visitor,
            Viewport viewport,
            float searchDistance,
            boolean useOcclusionCulling,
            int frame,
            Operation<Void> original
    ) {
        if (!WatheClient.isTrainMoving() || WatheClient.trainComponent == null || this.wathe$trainOcclusionCuller == null) {
            original.call(instance, visitor, viewport, searchDistance, useOcclusionCulling, frame);
            return;
        }

        SceneryOcclusionVisitor dedupingVisitor = new SceneryOcclusionVisitor(visitor);

        // 高处列车本体仍走 Sodium 的图遍历；风景部分下面按虚拟位置额外加入。
        this.wathe$trainOcclusionCuller.findVisible(dedupingVisitor, viewport, searchDistance, useOcclusionCulling, frame);

        CameraTransform cameraTransform = viewport.getTransform();
        SceneryConfig sceneryConfig = wathe$getSceneryConfig();
        float trainSpeed = WatheClient.getTrainSpeed();
        int tileWidth = 15 * 16;
        int tileLength = 32 * 16;
        int tileSize = tileLength * 3;
        int height = sceneryConfig.heightOffset();
        float time = WatheClient.trainComponent.getTime()
                + MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true);
        float cameraX = cameraTransform.intX + cameraTransform.fracX;
        int cameraSectionZ = viewport.getChunkCoord().getSectionZ();
        float motionOffset = (time / 73.8f) * trainSpeed;

        boolean tileWidthChanged = tileWidth != this.wathe$lastTileWidth;
        boolean tileLengthChanged = tileLength != this.wathe$lastTileLength;
        boolean heightChanged = height != this.wathe$lastHeight;
        boolean cameraXChanged = cameraX != this.wathe$lastCameraX;
        boolean motionOffsetChanged = motionOffset != this.wathe$lastMotionOffset;

        this.wathe$lastTileWidth = tileWidth;
        this.wathe$lastTileLength = tileLength;
        this.wathe$lastHeight = height;
        this.wathe$lastCameraX = cameraX;
        this.wathe$lastMotionOffset = motionOffset;

        float halfTileSize = tileSize / 2.0f;
        float backwardMotionOffset = motionOffset - tileLength;
        float forwardMotionOffset = motionOffset + tileLength;

        for (var entry : this.wathe$scenerySectionsByOriginPosition.long2ReferenceEntrySet()) {
            SceneryRenderSection sceneryRenderSection = entry.getValue();
            RenderSection renderSection = sceneryRenderSection.getRenderSection();
            int zSectionFromCamera = renderSection.getChunkZ() - cameraSectionZ;

            if (sceneryRenderSection.init()) {
                sceneryRenderSection.updateY(height);
                sceneryRenderSection.updateZ(zSectionFromCamera, tileWidth);
                wathe$updateSceneryX(sceneryRenderSection, zSectionFromCamera, halfTileSize, tileSize,
                        motionOffset, backwardMotionOffset, forwardMotionOffset, cameraX);
            } else {
                if (heightChanged) {
                    sceneryRenderSection.updateY(height);
                }

                boolean zSectionChanged = zSectionFromCamera != sceneryRenderSection.getLastZSectionFromCamera();
                if (zSectionChanged || tileWidthChanged) {
                    sceneryRenderSection.updateZ(zSectionFromCamera, tileWidth);
                }

                if (zSectionChanged || motionOffsetChanged || tileLengthChanged || cameraXChanged) {
                    wathe$updateSceneryX(sceneryRenderSection, zSectionFromCamera, halfTileSize, tileSize,
                            motionOffset, backwardMotionOffset, forwardMotionOffset, cameraX);
                }
            }

            if (wathe$isSectionVisible(sceneryRenderSection, viewport, searchDistance)) {
                renderSection.setLastVisibleFrame(frame);
                dedupingVisitor.visit(renderSection);
            }
        }
    }

    @Unique
    private static SceneryConfig wathe$getSceneryConfig() {
        return WatheClient.mapEnhancementsWorldComponent != null
                ? WatheClient.mapEnhancementsWorldComponent.getSceneryConfig()
                : MapEnhancementsConfiguration.SceneryConfig.DEFAULT;
    }

    @Unique
    private static void wathe$updateSceneryX(
            SceneryRenderSection section,
            int zSectionFromCamera,
            float halfTileSize,
            int tileSize,
            float motionOffset,
            float backwardMotionOffset,
            float forwardMotionOffset,
            float cameraX
    ) {
        if (zSectionFromCamera <= -8) {
            section.updateX(halfTileSize, tileSize, backwardMotionOffset, cameraX);
        } else if (zSectionFromCamera >= 8) {
            section.updateX(halfTileSize, tileSize, forwardMotionOffset, cameraX);
        } else {
            section.updateX(halfTileSize, tileSize, motionOffset, cameraX);
        }
    }

    @Unique
    private static Frustum wathe$getFrustum(Viewport viewport) {
        return ((ViewportAccessor) (Object) viewport).wathe$getFrustum();
    }

    @Unique
    private static boolean wathe$isWithinFrustum(Viewport viewport, SceneryRenderSection section) {
        float originX = section.getVirtualCenterX() - (float) viewport.getTransform().x;
        float originY = (section.getVirtualCenterY() - viewport.getTransform().intY) - viewport.getTransform().fracY;
        float originZ = (section.getVirtualCenterZ() - viewport.getTransform().intZ) - viewport.getTransform().fracZ;

        return wathe$getFrustum(viewport).testAab(
                originX - CHUNK_SECTION_SIZE,
                originY - CHUNK_SECTION_SIZE,
                originZ - CHUNK_SECTION_SIZE,
                originX + CHUNK_SECTION_SIZE,
                originY + CHUNK_SECTION_SIZE,
                originZ + CHUNK_SECTION_SIZE
        );
    }

    @Unique
    private static float wathe$nearestToZero(float min, float max) {
        if (min > 0.0f) {
            return min;
        }

        if (max < 0.0f) {
            return max;
        }

        return 0.0f;
    }

    @Unique
    private static boolean wathe$isWithinRenderDistance(CameraTransform camera, SceneryRenderSection section, float maxDistance) {
        float ox = (float) (section.getVirtualOriginX() - camera.x);
        float oy = section.getVirtualOriginY() - camera.intY;
        float oz = section.getVirtualOriginZ() - camera.intZ;
        float dx = wathe$nearestToZero(ox, ox + 16.0f);
        float dy = wathe$nearestToZero(oy, oy + 16.0f) - camera.fracY;
        float dz = wathe$nearestToZero(oz, oz + 16.0f) - camera.fracZ;

        return (dx * dx + dz * dz) < (maxDistance * maxDistance) && Math.abs(dy) < maxDistance;
    }

    @Unique
    private static boolean wathe$isSectionVisible(SceneryRenderSection section, Viewport viewport, float maxDistance) {
        return wathe$isWithinRenderDistance(viewport.getTransform(), section, maxDistance)
                && wathe$isWithinFrustum(viewport, section);
    }
}
