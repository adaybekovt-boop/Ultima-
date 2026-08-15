package dev.ultima.mixin.terrain_metrics;

import dev.ultima.client.metrics.TerrainFrameMetrics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void ultimaTerrainMetricsBeginFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        TerrainFrameMetrics.beginFrame();
    }
}
