package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void ultimaBeginEntities(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.TICK_ENTITIES);
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V",
                    shift = At.Shift.AFTER))
    private void ultimaEndEntities(final BooleanSupplier haveTime, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_ENTITIES);
    }

    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void ultimaBeginRandomTicks(final LevelChunk chunk, final int tickSpeed, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.TICK_RANDOM_TICKS);
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void ultimaEndRandomTicks(final LevelChunk chunk, final int tickSpeed, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_RANDOM_TICKS);
    }
}
