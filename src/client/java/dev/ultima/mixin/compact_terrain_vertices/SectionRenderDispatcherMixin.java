package dev.ultima.mixin.compact_terrain_vertices;

import dev.ultima.vertex.CompactTerrainVertex;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * SOLID and CUTOUT uber heaps use the compact stride. TRANSLUCENT stays on
 * vanilla BLOCK alignment because it remains on the vanilla submit path.
 * Layer identity comes from {@code UberGpuBuffer}'s name (the layer label),
 * not from constructor call order.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {
    @ModifyArgs(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/UberGpuBuffer;<init>(Ljava/lang/String;IIILcom/mojang/blaze3d/vertex/StagingBuffer;)V"))
    private void ultimaCompactAlignSize(final Args args) {
        String name = args.get(0);
        int alignSize = args.get(3);
        args.set(3, CompactTerrainVertex.uberAlignSize(name, alignSize));
    }
}
