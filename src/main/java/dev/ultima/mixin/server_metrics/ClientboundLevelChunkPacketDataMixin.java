package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataMixin {
    @Shadow
    @Final
    private byte[] buffer;

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("RETURN"))
    private void ultimaPreparedBytes(final LevelChunk levelChunk, final CallbackInfo ci) {
        if (this.buffer != null) {
            ServerMetrics.addCount(MetricId.PACKETS_PREPARED_BYTES, this.buffer.length);
        }
    }
}
