package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void ultimaBeginChunkManager(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.TICK_CHUNK_MANAGER);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void ultimaEndChunkManager(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_CHUNK_MANAGER);
    }

    @Inject(
            method = "markChunkPendingToSend(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"))
    private static void ultimaPlayerEnteredChunk(final ServerPlayer player, final LevelChunk chunk, final CallbackInfo ci) {
        if (ServerMetrics.isDetailed()) {
            ServerMetrics.recordPlayerEnteredChunk(player.getScoreboardName());
        } else {
            ServerMetrics.addCount(MetricId.PLAYER_CHUNKS_ENTERED, 1);
        }
    }
}
