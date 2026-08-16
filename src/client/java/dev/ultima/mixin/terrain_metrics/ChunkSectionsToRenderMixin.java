package dev.ultima.mixin.terrain_metrics;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkSectionsToRender.class, priority = 900)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), order = 100)
    private void ultimaBeginSubmit(final ChunkSectionLayerGroup group, final GpuSampler sampler, final CallbackInfo ci) {
        TerrainFrameMetrics.beginSubmit(group);
    }

    @Inject(method = "renderGroup", at = @At("RETURN"), order = 100)
    private void ultimaEndSubmit(final ChunkSectionLayerGroup group, final GpuSampler sampler, final CallbackInfo ci) {
        TerrainFrameMetrics.endSubmit(group);
    }
}
