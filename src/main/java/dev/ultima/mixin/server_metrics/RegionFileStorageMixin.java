package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Inject(method = "read", at = @At("HEAD"))
    private void ultimaBeginRead(final ChunkPos pos, final CallbackInfoReturnable<CompoundTag> cir) {
        ServerMetrics.begin(MetricId.CHUNK_IO);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void ultimaEndRead(final ChunkPos pos, final CallbackInfoReturnable<CompoundTag> cir) {
        ServerMetrics.end(MetricId.CHUNK_IO);
    }

    @Inject(method = "write", at = @At("HEAD"))
    private void ultimaBeginWrite(final ChunkPos pos, final CompoundTag value, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.CHUNK_IO);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void ultimaEndWrite(final ChunkPos pos, final CompoundTag value, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.CHUNK_IO);
    }
}
