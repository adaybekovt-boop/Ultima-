package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "doFill", at = @At("HEAD"))
    private void ultimaBeginNoiseFill(
            final Blender blender,
            final StructureManager structureManager,
            final RandomState randomState,
            final ChunkAccess centerChunk,
            final int cellMinY,
            final int cellCountY,
            final CallbackInfoReturnable<ChunkAccess> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "doFill", at = @At("RETURN"))
    private void ultimaEndNoiseFill(
            final Blender blender,
            final StructureManager structureManager,
            final RandomState randomState,
            final ChunkAccess centerChunk,
            final int cellMinY,
            final int cellCountY,
            final CallbackInfoReturnable<ChunkAccess> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }
}
