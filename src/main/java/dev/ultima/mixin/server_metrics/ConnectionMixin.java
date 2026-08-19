package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    @Final
    private PacketFlow receiving;

    @Shadow
    private Channel channel;

    @Inject(method = "tick", at = @At("HEAD"))
    private void ultimaQueuedBytes(final CallbackInfo ci) {
        if (this.receiving != PacketFlow.SERVERBOUND || this.channel == null || !this.channel.isOpen()) {
            return;
        }
        try {
            ChannelOutboundBuffer outbound = this.channel.unsafe().outboundBuffer();
            if (outbound != null) {
                ServerMetrics.addCount(MetricId.NETWORK_QUEUED_BYTES, outbound.totalPendingWriteBytes());
            }
        } catch (RuntimeException ignored) {
            // Fail open: queue sampling must never disconnect a player.
        }
    }
}
