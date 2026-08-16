package dev.ultima.mixin.java_mesher;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ultima.client.renderer.mesh.PackedSectionScan;
import dev.ultima.config.UltimaConfig;
import dev.ultima.lab.LabFeatureGates;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same tessellation as vanilla {@code SectionCompiler.compile}, with a packed
 * x-fastest visit order (matching {@code BlockPos.betweenClosed}) and
 * worker-thread renderer reuse. Geometry, materials, tint, and lighting stay
 * on the vanilla tessellators.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow
    @Final
    private boolean ambientOcclusion;

    @Shadow
    @Final
    private boolean cutoutLeaves;

    @Shadow
    @Final
    private BlockStateModelSet blockModelSet;

    @Shadow
    @Final
    private FluidStateModelSet fluidModelSet;

    @Shadow
    @Final
    private BlockColors blockColors;

    @Shadow
    private BufferBuilder getOrBeginLayer(
            Map<ChunkSectionLayer, BufferBuilder> startedLayers,
            SectionBufferBuilderPack buffers,
            ChunkSectionLayer layer) {
        throw new AssertionError();
    }

    @Shadow
    private <E extends BlockEntity> void handleBlockEntity(SectionCompiler.Results results, E blockEntity) {
        throw new AssertionError();
    }

    @Unique
    private static final ThreadLocal<MutableBlockPos> ULTIMA$POS = ThreadLocal.withInitial(MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<SectionCompilerMixin.RendererCache> ULTIMA$RENDERERS =
            ThreadLocal.withInitial(SectionCompilerMixin.RendererCache::new);

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void ultimaPackedCompile(
            final SectionPos sectionPos,
            final RenderSectionRegion region,
            final VertexSorting vertexSorting,
            final SectionBufferBuilderPack builders,
            final CallbackInfoReturnable<SectionCompiler.Results> cir) {
        if (UltimaConfig.get().isEnabled(LabFeatureGates.DATA_MESHER)) {
            return;
        }
        SectionCompiler.Results results = new SectionCompiler.Results();
        BlockPos minPos = sectionPos.origin();
        VisGraph visGraph = new VisGraph();
        BlockModelLighter.enableCaching();
        RendererCache cache = ULTIMA$RENDERERS.get();
        ModelBlockRenderer blockRenderer = cache.blockRenderer(this.ambientOcclusion, this.blockColors);
        FluidRenderer fluidRenderer = cache.fluidRenderer(this.fluidModelSet);
        Map<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
        BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
            BufferBuilder builder = this.getOrBeginLayer(startedLayers, builders, quad.materialInfo().layer());
            builder.putBlockBakedQuad(x, y, z, quad, instance);
        };
        BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> {
            BufferBuilder builder = this.getOrBeginLayer(startedLayers, builders, ChunkSectionLayer.SOLID);
            builder.putBlockBakedQuad(x, y, z, quad, instance);
        };
        FluidRenderer.Output fluidOutput = layerx -> this.getOrBeginLayer(startedLayers, builders, layerx);
        MutableBlockPos pos = ULTIMA$POS.get();

        PackedSectionScan.visit(minPos.getX(), minPos.getY(), minPos.getZ(), (worldX, worldY, worldZ, localX, localY, localZ) -> {
            pos.set(worldX, worldY, worldZ);
            BlockState blockState = region.getBlockState(pos);
            if (blockState.isAir()) {
                return;
            }
            try {
                if (blockState.isSolidRender()) {
                    visGraph.setOpaque(pos);
                }
                if (blockState.hasBlockEntity()) {
                    BlockEntity blockEntity = region.getBlockEntity(pos);
                    if (blockEntity != null) {
                        this.handleBlockEntity(results, blockEntity);
                    }
                }
                FluidState fluidState = blockState.getFluidState();
                if (!fluidState.isEmpty()) {
                    fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
                }
                if (blockState.getRenderShape() == RenderShape.MODEL) {
                    blockRenderer.tesselateBlock(
                            ModelBlockRenderer.forceOpaque(this.cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
                            localX,
                            localY,
                            localZ,
                            region,
                            pos,
                            blockState,
                            this.blockModelSet.get(blockState),
                            blockState.getSeed(pos));
                }
            } catch (Throwable t) {
                CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
                CrashReportCategory category = report.addCategory("Block being tesselated");
                CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
                throw new ReportedException(report);
            }
        });

        for (Entry<ChunkSectionLayer, BufferBuilder> entry : startedLayers.entrySet()) {
            ChunkSectionLayer layer = entry.getKey();
            MeshData mesh = entry.getValue().build();
            if (mesh != null) {
                if (layer == ChunkSectionLayer.TRANSLUCENT) {
                    results.transparencyState = mesh.sortQuads(builders.buffer(layer), vertexSorting);
                }
                results.renderedLayers.put(layer, mesh);
            }
        }

        BlockModelLighter.clearCache();
        results.visibilitySet = visGraph.resolve();
        cir.setReturnValue(results);
    }

    private static final class RendererCache {
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
