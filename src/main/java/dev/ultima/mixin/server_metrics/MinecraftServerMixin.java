package dev.ultima.mixin.server_metrics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @WrapMethod(method = "tickServer")
    private void ultimaTickServer(final BooleanSupplier haveTime, final Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        ServerMetrics.beginTick(server.getTickCount(), server.getPlayerCount());
        ServerMetrics.begin(MetricId.TICK_TOTAL);
        try {
            original.call(haveTime);
        } finally {
            ServerMetrics.end(MetricId.TICK_TOTAL);
            ServerMetrics.endTick();
        }
    }
}
