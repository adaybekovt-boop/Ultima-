package dev.ultima.mixin.terrain_metrics;

import dev.ultima.client.metrics.TerrainFrameMetrics;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionTaskDynamicQueue.class)
public abstract class SectionTaskDynamicQueueMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void ultimaRebuildScheduled(final SectionRenderDispatcher.RenderSection.SectionTask task, final CallbackInfo ci) {
        TerrainFrameMetrics.incrementRebuilds();
    }
}
