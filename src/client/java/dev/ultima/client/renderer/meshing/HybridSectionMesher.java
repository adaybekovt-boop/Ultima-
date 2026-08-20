package dev.ultima.client.renderer.meshing;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ultima.client.renderer.snapshot.RenderSectionSnapshot;
import dev.ultima.meshing.BlockRenderFlags;
import dev.ultima.meshing.FastPathCriteria;
import dev.ultima.meshing.MesherCircuitBreaker;
import dev.ultima.meshing.MesherMetrics;
import dev.ultima.meshing.PackedSectionVolume;
import dev.ultima.meshing.SectionIndex;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Hybrid section compile: packed 18³ snapshot, x-fastest visit, unit-cube fast
 * path, vanilla {@code ModelBlockRenderer}/{@code FluidRenderer} fallback.
 *
 * Fast-path geometry is vanilla {@code BakedQuad}s with vanilla lighting, tint,
 * and {@code Block.shouldRenderFace}. {@code tesselateBlock} is not called for
 * those cells.
 */
public final class HybridSectionMesher {
    private static final Direction[] DIRECTIONS = Direction.values();
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

    private HybridSectionMesher() {
    }

    /**
     * Bind the worker-thread cube cache to the current model/atlas generation.
     * Resource reload replaces the {@code BlockStateModelSet} identity; the
     * long-lived {@code ThreadLocal} scratch must drop stale baked quads.
     */
    public static CubeModelCache bindWorkerCubeCache(final Object modelSetIdentity) {
        return SCRATCH.get().cubeCache(modelSetIdentity);
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
        BlockPos origin = sectionPos.origin();
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        VisGraph visGraph = new VisGraph();
        BlockModelLighter.enableCaching();
        ModelBlockRenderer blockRenderer = scratch.blockRenderer(ambientOcclusion, blockColors);
        FluidRenderer fluidRenderer = scratch.fluidRenderer(fluidModelSet);
        CubeModelCache cache = scratch.cubeCache(blockModelSet);
        BlockModelLighter lighter = scratch.lighter;
        QuadInstance quadInstance = scratch.quadInstance;
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
        MutableBlockPos neighborPos = scratch.neighborPos;

        long allocatedBefore = MesherMetrics.threadAllocatedBytes();
        long snapshotStart = System.nanoTime();
        scratch.snapshot.capture(region, originX, originY, originZ, pos);
        long snapshotNs = System.nanoTime() - snapshotStart;
        PackedSectionVolume volume = scratch.snapshot.volume();
        RenderSectionSnapshot view = scratch.snapshot;

        long meshStart = System.nanoTime();
        long blocksVisited = 0L;
        long fastPathBlocks = 0L;
        long fallbackBlocks = 0L;
        long modelCalls = 0L;
        long fluidCalls = 0L;
        MesherCircuitBreaker breaker = MesherCircuitBreaker.get();
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
                        if (blockState.getRenderShape() != RenderShape.MODEL) {
                            fallbackBlocks++;
                            MesherMetrics.recordDecision(FastPathCriteria.Result.fallback(FastPathCriteria.Reason.NOT_MODEL));
                            continue;
                        }
                        BlockStateModel model = blockModelSet.get(blockState);
                        if (!breaker.allowFastPath(blockState)) {
                            fallbackBlocks++;
                            modelCalls++;
                            MesherMetrics.recordDecision(FastPathCriteria.Result.fallback(FastPathCriteria.Reason.CIRCUIT_BREAKER));
                            blockRenderer.tesselateBlock(
                                    ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
                                    x,
                                    y,
                                    z,
                                    view,
                                    pos,
                                    blockState,
                                    model,
                                    blockState.getSeed(pos));
                            continue;
                        }
                        CubeModelCache.Lookup lookup = cache.lookup(
                                blockState, model, cutoutLeaves, blockState.getSeed(pos));
                        if (lookup.fastPath()) {
                            try {
                                tessellateFastCube(
                                        lookup.cube(),
                                        quadOutput,
                                        x,
                                        y,
                                        z,
                                        view,
                                        pos,
                                        neighborPos,
                                        blockState,
                                        ambientOcclusion,
                                        lighter,
                                        quadInstance,
                                        scratch,
                                        blockColors);
                                fastPathBlocks++;
                                MesherMetrics.recordDecision(lookup.result());
                            } catch (Throwable error) {
                                if (breaker.recordFailure(blockState)) {
                                    MesherMetrics.recordCircuitBreakerTrip();
                                }
                                discardStartedLayers(startedLayers);
                                throw error;
                            }
                        } else {
                            fallbackBlocks++;
                            modelCalls++;
                            MesherMetrics.recordDecision(lookup.result());
                            blockRenderer.tesselateBlock(
                                    ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
                                    x,
                                    y,
                                    z,
                                    view,
                                    pos,
                                    blockState,
                                    model,
                                    blockState.getSeed(pos));
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
                    fastPathBlocks,
                    fallbackBlocks,
                    modelCalls,
                    fluidCalls,
                    vertices,
                    bytes,
                    allocationProxy,
                    0L);
            return results;
        } catch (Throwable error) {
            results.release();
            discardStartedLayers(startedLayers);
            throw error;
        } finally {
            BlockModelLighter.clearCache();
            startedLayers.clear();
        }
    }

    /**
     * Finish and drop any in-progress layer so the shared
     * {@code SectionBufferBuilderPack} can be reused by vanilla compile.
     * 26.2 {@code BufferBuilder} has no {@code discard()}; {@code build()}
     * then {@code MeshData.close()} is the public way to free the
     * {@code ByteBufferBuilder} slice.
     */
    static void discardStartedLayers(final Map<ChunkSectionLayer, BufferBuilder> startedLayers) {
        for (BufferBuilder builder : startedLayers.values()) {
            try {
                MeshData mesh = builder.build();
                if (mesh != null) {
                    mesh.close();
                }
            } catch (Throwable ignored) {
                // Already built, or mid-vertex: vanilla fail-open still runs.
            }
        }
        startedLayers.clear();
    }

    /**
     * Production culling + AO decision for a fast-path cube. {@link #tessellateFastCube}
     * is the only caller; tests invoke this method with real {@link Block#shouldRenderFace}
     * rather than grepping the source.
     */
    static void forEachVisibleFastCubeFace(
            final BlockState state,
            final BlockGetter view,
            final BlockPos pos,
            final MutableBlockPos neighborPos,
            final boolean ambientOcclusion,
            final boolean cubeUsesAmbientOcclusion,
            final VisibleFastCubeFace consumer) {
        boolean useAo = ambientOcclusion && state.getLightEmission() == 0 && cubeUsesAmbientOcclusion;
        for (Direction direction : DIRECTIONS) {
            neighborPos.setWithOffset(pos, direction);
            if (!Block.shouldRenderFace(state, view.getBlockState(neighborPos), direction)) {
                continue;
            }
            consumer.accept(direction, useAo);
        }
    }

    @FunctionalInterface
    interface VisibleFastCubeFace {
        void accept(Direction direction, boolean useAo);
    }

    static void tessellateFastCube(
            final CubeModelCache.CachedCube cube,
            final BlockQuadOutput output,
            final float localX,
            final float localY,
            final float localZ,
            final RenderSectionSnapshot view,
            final BlockPos pos,
            final MutableBlockPos neighborPos,
            final BlockState state,
            final boolean ambientOcclusion,
            final BlockModelLighter lighter,
            final QuadInstance quadInstance,
            final Scratch scratch,
            final BlockColors blockColors) {
        scratch.resetTint();
        forEachVisibleFastCubeFace(
                state,
                view,
                pos,
                neighborPos,
                ambientOcclusion,
                cube.useAmbientOcclusion(),
                (direction, useAo) -> {
                    BakedQuad quad = cube.quad(direction);
                    if (useAo) {
                        lighter.prepareQuadAmbientOcclusion(view, state, pos, quad, quadInstance);
                    } else {
                        int lightCoords = lighter.getLightCoords(state, view, neighborPos);
                        lighter.prepareQuadFlat(view, state, pos, lightCoords, quad, quadInstance);
                    }
                    applyTint(quadInstance, view, state, pos, quad, scratch, blockColors);
                    output.put(localX, localY, localZ, quad, quadInstance);
                });
    }

    private static void applyTint(
            final QuadInstance instance,
            final RenderSectionSnapshot view,
            final BlockState state,
            final BlockPos pos,
            final BakedQuad quad,
            final Scratch scratch,
            final BlockColors blockColors) {
        int tintIndex = quad.materialInfo().tintIndex();
        if (tintIndex == -1) {
            return;
        }
        if (scratch.tintCacheIndex == tintIndex) {
            instance.multiplyColor(scratch.tintCacheValue);
            return;
        }
        BlockTintSource source = blockColors.getTintSource(state, tintIndex);
        int color = source == null ? -1 : source.colorInWorld(state, view, pos);
        scratch.tintCacheIndex = tintIndex;
        scratch.tintCacheValue = color;
        instance.multiplyColor(color);
    }

    private static final class Scratch {
        private final RenderSectionSnapshot snapshot = new RenderSectionSnapshot();
        private final MutableBlockPos pos = new MutableBlockPos();
        private final MutableBlockPos neighborPos = new MutableBlockPos();
        private final EnumMap<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
        private final CubeModelCache cubeCache = new CubeModelCache();
        private final BlockModelLighter lighter = new BlockModelLighter();
        private final QuadInstance quadInstance = new QuadInstance();
        private boolean ambientOcclusion;
        private BlockColors blockColors;
        private FluidStateModelSet fluidModelSet;
        private ModelBlockRenderer blockRenderer;
        private FluidRenderer fluidRenderer;
        private int tintCacheIndex = -1;
        private int tintCacheValue;

        private void resetTint() {
            this.tintCacheIndex = -1;
        }

        private CubeModelCache cubeCache(final Object blockModelSet) {
            this.cubeCache.bindModelSet(blockModelSet);
            return this.cubeCache;
        }

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
