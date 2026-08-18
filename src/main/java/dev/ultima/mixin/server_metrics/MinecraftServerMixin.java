package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void ultimaBeginServerTick(final BooleanSupplier haveTime, final CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer)(Object)this;
        ServerMetrics.beginTick(server.getTickCount(), server.getPlayerCount());
        ServerMetrics.begin(MetricId.TICK_TOTAL);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void ultimaEndServerTick(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_TOTAL);
        ServerMetrics.endTick();
    }
}
