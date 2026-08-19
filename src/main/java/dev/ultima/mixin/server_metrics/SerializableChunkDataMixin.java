package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
    @Inject(method = "parse", at = @At("HEAD"))
    private static void ultimaBeginLoad(
            final LevelHeightAccessor levelHeight,
            final PalettedContainerFactory containerFactory,
            final CompoundTag chunkData,
            final CallbackInfoReturnable<SerializableChunkData> cir) {
        ServerMetrics.begin(MetricId.CHUNK_LOAD);
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void ultimaEndLoad(
            final LevelHeightAccessor levelHeight,
            final PalettedContainerFactory containerFactory,
            final CompoundTag chunkData,
            final CallbackInfoReturnable<SerializableChunkData> cir) {
        ServerMetrics.end(MetricId.CHUNK_LOAD);
    }

    @Inject(method = "copyOf", at = @At("HEAD"))
    private static void ultimaBeginCopy(
            final ServerLevel level,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<SerializableChunkData> cir) {
        ServerMetrics.begin(MetricId.CHUNK_SERIALIZE);
    }

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void ultimaEndCopy(
            final ServerLevel level,
            final ChunkAccess chunk,
            final CallbackInfoReturnable<SerializableChunkData> cir) {
        ServerMetrics.end(MetricId.CHUNK_SERIALIZE);
    }

    @Inject(method = "write", at = @At("HEAD"))
    private void ultimaBeginWrite(final CallbackInfoReturnable<CompoundTag> cir) {
        ServerMetrics.begin(MetricId.CHUNK_SERIALIZE);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void ultimaEndWrite(final CallbackInfoReturnable<CompoundTag> cir) {
        ServerMetrics.end(MetricId.CHUNK_SERIALIZE);
    }
}
