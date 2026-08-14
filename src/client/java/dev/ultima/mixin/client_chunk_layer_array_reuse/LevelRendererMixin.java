package dev.ultima.mixin.client_chunk_layer_array_reuse;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.client.benchmark.ClientOptimizationCounters;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code Enum.values()} clones its backing array. Chunk preparation calls it once for setup and once
 * for every visible section even though the method only iterates the returned array. Reusing the
 * first array for the rest of the invocation removes per-section array churn without changing layer
 * membership or order.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Unique
    private ChunkSectionLayer @Nullable [] ultimaChunkLayers;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"))
    private void ultimaBeginChunkPreparation(
            final Matrix4fc modelViewMatrix,
            final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        this.ultimaChunkLayers = null;
    }

    @WrapOperation(
            method = "prepareChunkRenders",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"),
            expect = 2)
    private ChunkSectionLayer[] ultimaReuseChunkLayers(final Operation<ChunkSectionLayer[]> original) {
        ChunkSectionLayer[] layers = this.ultimaChunkLayers;
        if (layers == null) {
            layers = original.call();
            this.ultimaChunkLayers = layers;
        } else {
            ClientOptimizationCounters.avoidedChunkLayerArray();
        }
        return layers;
    }
}
