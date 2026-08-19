package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(method = "generateStructureStarts", at = @At("HEAD"))
    private static void ultimaBeginStructures(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateStructureStarts", at = @At("RETURN"))
    private static void ultimaEndStructures(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateStructureReferences", at = @At("HEAD"))
    private static void ultimaBeginStructureRefs(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateStructureReferences", at = @At("RETURN"))
    private static void ultimaEndStructureRefs(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateBiomes", at = @At("HEAD"))
    private static void ultimaBeginBiomes(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateBiomes", at = @At("RETURN"))
    private static void ultimaEndBiomes(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateSurface", at = @At("HEAD"))
    private static void ultimaBeginSurface(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateSurface", at = @At("RETURN"))
    private static void ultimaEndSurface(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"))
    private static void ultimaBeginCarvers(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private static void ultimaEndCarvers(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }

    @Inject(method = "generateFeatures", at = @At("HEAD"))
    private static void ultimaBeginFeatures(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.begin(MetricId.CHUNK_GENERATE);
        ServerMetrics.addCount(MetricId.CHUNKS_GENERATED, 1);
    }

    @Inject(method = "generateFeatures", at = @At("RETURN"))
    private static void ultimaEndFeatures(
            final WorldGenContext context,
            final ChunkStep step,
            final StaticCache2D<GenerationChunkHolder> chunks,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<?> cir) {
        ServerMetrics.end(MetricId.CHUNK_GENERATE);
    }
}
