package dev.ultima.mixin.retained_terrain;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.ultima.client.renderer.retained.RetainedTerrainRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void ultimaRetainedOpaque(final ChunkSectionLayerGroup group, final GpuSampler sampler, final CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.OPAQUE) {
            return;
        }
        RetainedTerrainRenderer renderer = RetainedTerrainRenderer.get();
        if (!renderer.isOpaqueReady()) {
            return;
        }
        renderer.submitOpaque(sampler);
        ci.cancel();
    }
}
