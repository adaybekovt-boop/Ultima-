package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CipherEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CipherEncoder.class)
public abstract class CipherEncoderMixin {
    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            at = @At("HEAD"))
    private void ultimaBeginEncrypt(final ChannelHandlerContext ctx, final ByteBuf msg, final ByteBuf out, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.NETWORK_ENCRYPT);
    }

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            at = @At("RETURN"))
    private void ultimaEndEncrypt(final ChannelHandlerContext ctx, final ByteBuf msg, final ByteBuf out, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.NETWORK_ENCRYPT);
    }
}
