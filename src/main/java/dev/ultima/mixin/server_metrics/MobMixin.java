package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void ultimaBeginAi(final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.TICK_AI);
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void ultimaEndAi(final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_AI);
    }
}
