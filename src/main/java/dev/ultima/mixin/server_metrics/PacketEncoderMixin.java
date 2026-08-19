package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("HEAD"))
    private void ultimaBeginEncode(final ChannelHandlerContext ctx, final Packet<?> packet, final ByteBuf output, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.NETWORK_ENCODE);
    }

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"))
    private void ultimaEndEncode(final ChannelHandlerContext ctx, final Packet<?> packet, final ByteBuf output, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.NETWORK_ENCODE);
    }
}
