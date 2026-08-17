package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import dev.ultima.server.metrics.ServerTelemetry;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Shadow
    private SectionPos lastSectionPos;

    @Inject(method = "updatePlayers", at = @At("HEAD"))
    private void ultimaTrackingCandidates(final List<ServerPlayer> players, final CallbackInfo ci) {
        ServerMetrics.addCount(MetricId.TRACKING_ENTITY_CANDIDATES, players.size());
        if (ServerMetrics.isDetailed()
                && this.entity != null
                && !Objects.equals(this.lastSectionPos, SectionPos.of(this.entity))) {
            ServerTelemetry.recordTrackerRecalcSectionBoundary();
        }
    }

    @Inject(
            method = "updatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity;addPairing(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void ultimaTrackingAdded(final ServerPlayer player, final CallbackInfo ci) {
        ServerMetrics.addCount(MetricId.TRACKING_ADDED, 1);
    }

    @Inject(
            method = "removePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity;removePairing(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void ultimaTrackingRemoved(final ServerPlayer player, final CallbackInfo ci) {
        ServerMetrics.addCount(MetricId.TRACKING_REMOVED, 1);
    }
}
