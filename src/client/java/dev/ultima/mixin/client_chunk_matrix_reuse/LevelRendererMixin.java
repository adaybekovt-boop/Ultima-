package dev.ultima.mixin.client_chunk_matrix_reuse;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.client.benchmark.ClientOptimizationCounters;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every renderable chunk section receives the same model-view matrix in
 * {@link LevelRenderer#prepareChunkRenders}. Vanilla copies that matrix once per section, then
 * synchronously serializes every uniform before the method returns. One snapshot per invocation is
 * therefore sufficient and preserves the exact matrix bytes submitted for every section.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Unique
    private @Nullable Matrix4f ultimaChunkModelView;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"))
    private void ultimaBeginChunkPreparation(
            final Matrix4fc modelViewMatrix,
            final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        this.ultimaChunkModelView = null;
    }

    @WrapOperation(
            method = "prepareChunkRenders",
            at = @At(value = "NEW", target = "Lorg/joml/Matrix4f;"))
    private Matrix4f ultimaReuseChunkModelView(
            final Matrix4fc modelViewMatrix,
            final Operation<Matrix4f> original) {
        Matrix4f snapshot = this.ultimaChunkModelView;
        if (snapshot == null) {
            snapshot = original.call(modelViewMatrix);
            this.ultimaChunkModelView = snapshot;
        } else {
            ClientOptimizationCounters.avoidedChunkMatrixCopy();
        }
        return snapshot;
    }
}
