package dev.ultima.mixin.mesher_fast_path;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ultima.client.renderer.meshing.HybridSectionMesher;
import dev.ultima.meshing.MesherMetrics;
import dev.ultima.meshing.MesherSectionFailOpen;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void ultimaHybridFastPathCompile(
            final SectionPos sectionPos,
            final RenderSectionRegion region,
            final VertexSorting vertexSorting,
            final SectionBufferBuilderPack builders,
            final CallbackInfoReturnable<SectionCompiler.Results> cir) {
        try {
            cir.setReturnValue(HybridSectionMesher.compile(
                    sectionPos,
                    region,
                    vertexSorting,
                    builders,
                    this.ambientOcclusion,
                    this.cutoutLeaves,
                    this.blockModelSet,
                    this.fluidModelSet,
                    this.blockColors,
                    this::getOrBeginLayer,
                    this::handleBlockEntity));
        } catch (Throwable error) {
            MesherSectionFailOpen.log(sectionPos, error);
            MesherMetrics.recordSectionFailure();
            // Do not cancel: vanilla SectionCompiler.compile runs for this section only.
        }
    }
}
