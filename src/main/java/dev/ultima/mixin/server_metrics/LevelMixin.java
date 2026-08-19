package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void ultimaBeginBlockEntities(final CallbackInfo ci) {
        if ((Object)this instanceof ServerLevel) {
            ServerMetrics.begin(MetricId.TICK_BLOCK_ENTITIES);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void ultimaEndBlockEntities(final CallbackInfo ci) {
        if ((Object)this instanceof ServerLevel) {
            ServerMetrics.end(MetricId.TICK_BLOCK_ENTITIES);
        }
    }
}
