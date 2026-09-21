package dev.ultima.mixin.iris_shader_frontend_artifact_cache;

import dev.ultima.client.iris.cache.IrisFrontendArtifactCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Exact Iris 1.11.4 adapter; application is additionally gated by the official class fingerprint. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class TransformPatcherMixin {
    @Inject(method = "transform", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ultima$readGraphicsArtifact(
            final String name,
            final String vertex,
            final String geometry,
            final String tessControl,
            final String tessEval,
            final String fragment,
            final @Coerce Object parameters,
            final CallbackInfoReturnable<Object> cir) {
        Object hit = IrisFrontendArtifactCache.beginGraphics(
                name, vertex, geometry, tessControl, tessEval, fragment, parameters);
        if (hit != null) {
            cir.setReturnValue(hit);
        }
    }

    @Inject(method = "transform", at = @At("RETURN"), require = 0)
    private static void ultima$writeGraphicsArtifact(
            final String name,
            final String vertex,
            final String geometry,
            final String tessControl,
            final String tessEval,
            final String fragment,
            final @Coerce Object parameters,
            final CallbackInfoReturnable<Object> cir) {
        IrisFrontendArtifactCache.finish(cir.getReturnValue());
    }

    @Inject(method = "transformCompute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ultima$readComputeArtifact(
            final String name,
            final String compute,
            final @Coerce Object parameters,
            final CallbackInfoReturnable<Object> cir) {
        Object hit = IrisFrontendArtifactCache.beginCompute(name, compute, parameters);
        if (hit != null) {
            cir.setReturnValue(hit);
        }
    }

    @Inject(method = "transformCompute", at = @At("RETURN"), require = 0)
    private static void ultima$writeComputeArtifact(
            final String name,
            final String compute,
            final @Coerce Object parameters,
            final CallbackInfoReturnable<Object> cir) {
        IrisFrontendArtifactCache.finish(cir.getReturnValue());
    }
}
