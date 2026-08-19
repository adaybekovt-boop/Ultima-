package dev.ultima.mixin.server_metrics;

import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.ServerMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Brain.class)
public abstract class BrainMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void ultimaBeginBrain(final ServerLevel level, final LivingEntity body, final CallbackInfo ci) {
        ServerMetrics.begin(MetricId.TICK_AI);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void ultimaEndBrain(final ServerLevel level, final LivingEntity body, final CallbackInfo ci) {
        ServerMetrics.end(MetricId.TICK_AI);
    }
}
