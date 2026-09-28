package dev.ultima.mixin.iris_shader_frontend_artifact_cache;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultima.client.iris.cache.IrisFrontendArtifactCache;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Iris 1.11.4 keeps a 400-entry process-local LRU at the start of {@code transform} and
 * {@code transformCompute}, before {@code transformInternal}. Wrapping only the internal call
 * leaves that L1 in front: a same-JVM repeat never reaches Ultima or disk. The persistent cache
 * is consulted only after an Iris L1 miss.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class TransformPatcherMixin {
    @WrapOperation(
            method = {"transform", "transformCompute"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;transformInternal(Ljava/lang/String;Ljava/util/Map;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;"),
            require = 0)
    private static Map<Object, String> ultima$persistentArtifactAfterIrisL1Miss(
            final String name,
            final Map<Object, String> sources,
            final Object parameters,
            final Operation<Map<Object, String>> original) {
        @SuppressWarnings("unchecked")
        Operation<Map<?, ?>> bridge = (Operation<Map<?, ?>>) (Operation<?>) original;
        return (Map<Object, String>) IrisFrontendArtifactCache.aroundTransform(name, sources, parameters, bridge);
    }
}
