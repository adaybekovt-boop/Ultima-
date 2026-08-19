package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import java.util.BitSet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ClientboundLevelChunkWithLightPacketMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("HEAD"))
    private void ultimaBeginSendPrepare(
            final LevelChunk levelChunk,
            final LevelLightEngine lightEngine,
            final BitSet skyChangedLightSectionFilter,
            final BitSet blockChangedLightSectionFilter,
            final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.CHUNK_SEND_PREPARE);
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("RETURN"))
    private void ultimaEndSendPrepare(
            final LevelChunk levelChunk,
            final LevelLightEngine lightEngine,
            final BitSet skyChangedLightSectionFilter,
            final BitSet blockChangedLightSectionFilter,
            final CallbackInfo ci) {
        ServerMetrics.end(MetricId.CHUNK_SEND_PREPARE);
    }
}
