package dev.doctor4t.wathe.compat.sodium;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.minecraft.util.math.ChunkSectionPos;

/**
 * 合并 train/scenery 两条可见性路径的访问结果。
 *
 * <p>Sodium 最终按 section 坐标收集 render list。列车本体和虚拟风景可能引用到同一个
 * section key，因此这里做一次去重，避免同帧重复提交同一 section。</p>
 */
public class SceneryOcclusionVisitor implements OcclusionCuller.Visitor {
    private final OcclusionCuller.Visitor delegate;
    private final LongSet visitedSections = new LongOpenHashSet();

    public SceneryOcclusionVisitor(OcclusionCuller.Visitor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void visit(RenderSection renderSection) {
        long key = ChunkSectionPos.asLong(renderSection.getChunkX(), renderSection.getChunkY(), renderSection.getChunkZ());

        if (this.visitedSections.add(key)) {
            this.delegate.visit(renderSection);
        }
    }
}
