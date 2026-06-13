package dev.doctor4t.wathe.compat.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;

/**
 * Sodium 渲染用的“风景 section 虚拟坐标”缓存。
 *
 * <p>风景方块本体仍留在原地图坐标，只在渲染时通过 offset 伪装成循环移动的远景。
 * 这样 RenderSectionManager 可以先按虚拟坐标做视距/视锥剔除，DefaultChunkRenderer
 * 再把同一份 offset 写给 shader，避免把整片风景都无脑提交给 GPU。</p>
 */
public class SceneryRenderSection {
    public static final Long2ReferenceMap<SceneryRenderSection> cache = new Long2ReferenceOpenHashMap<>();

    private final RenderSection renderSection;

    private float offsetX;
    private int offsetY;
    private int offsetZ;
    private int lastZSectionFromCamera = Integer.MIN_VALUE;
    private boolean initialized;

    private float virtualCenterX;
    private int virtualCenterY;
    private int virtualCenterZ;
    private float virtualOriginX;
    private int virtualOriginY;
    private int virtualOriginZ;

    public SceneryRenderSection(RenderSection renderSection) {
        this.renderSection = renderSection;
    }

    public boolean init() {
        if (this.initialized) {
            return false;
        }

        this.initialized = true;
        return true;
    }

    public void updateX(float offset, int tileSize, float motionOffset, float cameraX) {
        this.offsetX = this.calculateXOffset(this.renderSection.getCenterX(), offset, tileSize, motionOffset, cameraX);
        this.virtualCenterX = this.renderSection.getCenterX() + this.offsetX;
        this.virtualOriginX = this.renderSection.getOriginX() + this.offsetX;
    }

    public void updateY(int offset) {
        this.offsetY = offset;
        this.virtualCenterY = this.renderSection.getCenterY() + this.offsetY;
        this.virtualOriginY = this.renderSection.getOriginY() + this.offsetY;
    }

    public void updateZ(int zSectionFromCamera, int tileWidth) {
        this.lastZSectionFromCamera = zSectionFromCamera;

        if (zSectionFromCamera <= -8) {
            this.offsetZ = tileWidth;
        } else if (zSectionFromCamera >= 8) {
            this.offsetZ = -tileWidth;
        } else {
            // 回到相机附近的中间带时必须归零，避免沿用上一次远端 tile 的横向偏移。
            this.offsetZ = 0;
        }

        this.virtualCenterZ = this.renderSection.getCenterZ() + this.offsetZ;
        this.virtualOriginZ = this.renderSection.getOriginZ() + this.offsetZ;
    }

    private float calculateXOffset(int blockPosX, float offset, int tileSize, float motionOffset, float cameraX) {
        float sectionXFromCamera = blockPosX - cameraX;
        float virtualXFromCamera = ((sectionXFromCamera + motionOffset) % tileSize) - offset;

        return virtualXFromCamera - sectionXFromCamera;
    }

    public RenderSection getRenderSection() {
        return this.renderSection;
    }

    public float getOffsetX() {
        return this.offsetX;
    }

    public int getOffsetY() {
        return this.offsetY;
    }

    public int getOffsetZ() {
        return this.offsetZ;
    }

    public int getLastZSectionFromCamera() {
        return this.lastZSectionFromCamera;
    }

    public float getVirtualCenterX() {
        return this.virtualCenterX;
    }

    public int getVirtualCenterY() {
        return this.virtualCenterY;
    }

    public int getVirtualCenterZ() {
        return this.virtualCenterZ;
    }

    public float getVirtualOriginX() {
        return this.virtualOriginX;
    }

    public int getVirtualOriginY() {
        return this.virtualOriginY;
    }

    public int getVirtualOriginZ() {
        return this.virtualOriginZ;
    }
}
