package dev.ultima.mixin.compact_terrain_vertices;

import dev.ultima.vertex.CompactTerrainVertex;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * SOLID and CUTOUT uber heaps use the compact stride. TRANSLUCENT stays on
 * vanilla BLOCK alignment because it remains on the vanilla submit path.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {
    @Unique
    private int ultima$opaqueHeaps;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/UberGpuBuffer;<init>(Ljava/lang/String;IIILcom/mojang/blaze3d/vertex/StagingBuffer;)V"),
            index = 3)
    private int ultimaCompactAlignSize(final int alignSize) {
        if (alignSize == 8) {
            return 8;
        }
        int heap = this.ultima$opaqueHeaps++;
        if (heap >= 2) {
            return alignSize;
        }
        return CompactTerrainVertex.BYTES;
    }
}
