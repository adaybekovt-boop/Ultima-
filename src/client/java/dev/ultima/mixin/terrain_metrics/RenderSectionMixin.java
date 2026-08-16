package dev.ultima.mixin.terrain_metrics;

import dev.ultima.client.metrics.TerrainFrameMetrics;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void ultimaUploadedMesh(final SectionMesh sectionMesh, final CallbackInfoReturnable<SectionMesh> cir) {
        TerrainFrameMetrics.incrementUploads();
    }
}
