package dev.ultima.mixin.terrain_metrics;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererMixin {
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"), order = 100)
    private void ultimaBeginPrepare(final Matrix4fc modelViewMatrix, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        TerrainFrameMetrics.beginPrepare();
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"), order = 100)
    private void ultimaEndPrepare(final Matrix4fc modelViewMatrix, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        TerrainFrameMetrics.endPrepare();
        if (TerrainFrameMetrics.isRetainedActive()) {
            return;
        }
        ChunkSectionsToRender result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        int draws = 0;
        int sectionLayers = 0;
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> groups =
                result.drawGroupsPerLayer();
        for (Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> map : groups.values()) {
            for (List<RenderPass.Draw<GpuBufferSlice[]>> list : map.values()) {
                draws += list.size();
                sectionLayers += list.size();
            }
        }
        TerrainFrameMetrics.recordVisible(
                this.visibleSections.size(),
                sectionLayers,
                draws,
                result.chunkSectionInfos().length);
    }
}
