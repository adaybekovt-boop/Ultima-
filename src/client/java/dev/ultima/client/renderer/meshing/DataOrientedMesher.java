package dev.ultima.client.renderer.meshing;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ultima.client.renderer.snapshot.RenderSectionSnapshot;
import dev.ultima.meshing.BlockRenderFlags;
import dev.ultima.meshing.MesherMetrics;
import dev.ultima.meshing.PackedSectionVolume;
import dev.ultima.meshing.SectionIndex;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Data-oriented compile: packed snapshot, linear interior scan, worker scratch,
 * vanilla tessellators. Geometry, tint, lighting, and model random remain vanilla.
 */
public final class DataOrientedMesher {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    @FunctionalInterface
    public interface LayerBegin {
        BufferBuilder begin(
                Map<ChunkSectionLayer, BufferBuilder> startedLayers,
                SectionBufferBuilderPack buffers,
                ChunkSectionLayer layer);
    }

    @FunctionalInterface
    public interface BlockEntityHandler {
        <E extends BlockEntity> void accept(SectionCompiler.Results results, E blockEntity);
    }

    private DataOrientedMesher() {
    }

    public static SectionCompiler.Results compile(
            final SectionPos sectionPos,
            final RenderSectionRegion region,
            final VertexSorting vertexSorting,
            final SectionBufferBuilderPack builders,
            final boolean ambientOcclusion,
            final boolean cutoutLeaves,
            final BlockStateModelSet blockModelSet,
            final FluidStateModelSet fluidModelSet,
            final BlockColors blockColors,
            final LayerBegin layerBegin,
            final BlockEntityHandler blockEntityHandler) {
        Scratch scratch = SCRATCH.get();
        SectionCompiler.Results results = new SectionCompiler.Results();
        int originX = sectionPos.minBlockX();
        int originY = sectionPos.minBlockY();
        int originZ = sectionPos.minBlockZ();
        VisGraph visGraph = new VisGraph();
        BlockModelLighter.enableCaching();
        ModelBlockRenderer blockRenderer = scratch.blockRenderer(ambientOcclusion, blockColors);
        FluidRenderer fluidRenderer = scratch.fluidRenderer(fluidModelSet);
        Map<ChunkSectionLayer, BufferBuilder> startedLayers = scratch.startedLayers;
        startedLayers.clear();
        BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
            BufferBuilder builder = layerBegin.begin(startedLayers, builders, quad.materialInfo().layer());
            builder.putBlockBakedQuad(x, y, z, quad, instance);
        };
        BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> {
            BufferBuilder builder = layerBegin.begin(startedLayers, builders, ChunkSectionLayer.SOLID);
            builder.putBlockBakedQuad(x, y, z, quad, instance);
        };
        FluidRenderer.Output fluidOutput = layer -> layerBegin.begin(startedLayers, builders, layer);
        MutableBlockPos pos = scratch.pos;

        long allocatedBefore = MesherMetrics.threadAllocatedBytes();
        long snapshotStart = System.nanoTime();
        scratch.snapshot.capture(region, originX, originY, originZ, pos);
        long snapshotNs = System.nanoTime() - snapshotStart;
        PackedSectionVolume volume = scratch.snapshot.volume();
        RenderSectionSnapshot view = scratch.snapshot;

        long meshStart = System.nanoTime();
        long blocksVisited = 0L;
        long modelCalls = 0L;
        long fluidCalls = 0L;
        try {
            for (int z = 0; z < SectionIndex.INTERIOR; z++) {
                for (int y = 0; y < SectionIndex.INTERIOR; y++) {
                    for (int x = 0; x < SectionIndex.INTERIOR; x++) {
                        int packed = SectionIndex.interior(x, y, z);
                        int flags = volume.flags(packed);
                        if (BlockRenderFlags.air(flags)) {
                            continue;
                        }
                        blocksVisited++;
                        int worldX = originX + x;
                        int worldY = originY + y;
                        int worldZ = originZ + z;
                        pos.set(worldX, worldY, worldZ);
                        BlockState blockState = view.state(packed);
                        try {
                            if (BlockRenderFlags.solidRender(flags)) {
                                visGraph.setOpaque(pos);
                            }
                            if (BlockRenderFlags.hasBlockEntity(flags)) {
                                BlockEntity blockEntity = view.entity(volume.blockEntitySlot(packed));
                                if (blockEntity != null) {
                                    blockEntityHandler.accept(results, blockEntity);
                                }
                            }
                            if (BlockRenderFlags.hasFluid(flags)) {
                                FluidState fluidState = blockState.getFluidState();
                                fluidCalls++;
                                fluidRenderer.tesselate(view, pos, fluidOutput, blockState, fluidState);
                            }
                            if (BlockRenderFlags.model(flags)) {
                                modelCalls++;
                                blockRenderer.tesselateBlock(
                                        ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
                                        x,
                                        y,
                                        z,
                                        view,
                                        pos,
                                        blockState,
                                        blockModelSet.get(blockState),
                                        blockState.getSeed(pos));
                            }
                        } catch (Throwable t) {
                            CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
                            CrashReportCategory category = report.addCategory("Block being tesselated");
                            CrashReportCategory.populateBlockDetails(category, view, pos, blockState);
                            throw new ReportedException(report);
                        }
                    }
                }
            }

            long vertices = 0L;
            long bytes = 0L;
            for (Entry<ChunkSectionLayer, BufferBuilder> entry : startedLayers.entrySet()) {
                ChunkSectionLayer layer = entry.getKey();
                MeshData mesh = entry.getValue().build();
                if (mesh != null) {
                    if (layer == ChunkSectionLayer.TRANSLUCENT) {
                        results.transparencyState = mesh.sortQuads(builders.buffer(layer), vertexSorting);
                    }
                    results.renderedLayers.put(layer, mesh);
                    int vertexCount = mesh.drawState().vertexCount();
                    vertices += vertexCount;
                    bytes += (long)vertexCount * mesh.drawState().format().getVertexSize();
                }
            }
            results.visibilitySet = visGraph.resolve();
            long meshNs = System.nanoTime() - meshStart;
            long allocatedAfter = MesherMetrics.threadAllocatedBytes();
            long allocationProxy = allocatedBefore >= 0L && allocatedAfter >= allocatedBefore
                    ? allocatedAfter - allocatedBefore
                    : -1L;
            MesherMetrics.recordCompile(
                    snapshotNs,
                    meshNs,
                    blocksVisited,
                    modelCalls,
                    fluidCalls,
                    vertices,
                    bytes,
                    allocationProxy,
                    0L);
            return results;
        } finally {
            BlockModelLighter.clearCache();
            startedLayers.clear();
        }
    }

    private static final class Scratch {
        private final RenderSectionSnapshot snapshot = new RenderSectionSnapshot();
        private final MutableBlockPos pos = new MutableBlockPos();
        private final EnumMap<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
        private boolean ambientOcclusion;
        private BlockColors blockColors;
        private FluidStateModelSet fluidModelSet;
        private ModelBlockRenderer blockRenderer;
        private FluidRenderer fluidRenderer;

        private ModelBlockRenderer blockRenderer(final boolean ambientOcclusion, final BlockColors blockColors) {
            if (this.blockRenderer == null || this.ambientOcclusion != ambientOcclusion || this.blockColors != blockColors) {
                this.ambientOcclusion = ambientOcclusion;
                this.blockColors = blockColors;
                this.blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
            }
            return this.blockRenderer;
        }

        private FluidRenderer fluidRenderer(final FluidStateModelSet fluidModelSet) {
            if (this.fluidRenderer == null || this.fluidModelSet != fluidModelSet) {
                this.fluidModelSet = fluidModelSet;
                this.fluidRenderer = new FluidRenderer(fluidModelSet);
            }
            return this.fluidRenderer;
        }
    }
}
