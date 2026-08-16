package dev.ultima.mixin.retained_terrain;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import dev.ultima.client.renderer.retained.RetainedTerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkSectionsToRender.class, priority = 1100)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true, order = 1000)
    private void ultimaRetainedOpaque(final ChunkSectionLayerGroup group, final GpuSampler sampler, final CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.OPAQUE) {
            return;
        }
        RetainedTerrainRenderer renderer = RetainedTerrainRenderer.get();
        if (!renderer.isOpaqueReady()) {
            return;
        }
        if (renderer.submitOpaque(sampler)) {
            // Cancellable cancel adds a return the priority-900 terrain_metrics
            // @At("RETURN") hook never sees. Close the outer OPAQUE_SUBMIT
            // interval here so ON-side CPU totals and pairing stay valid.
            TerrainFrameMetrics.closeOpenOpaqueSubmit();
            ci.cancel();
        }
    }
}
