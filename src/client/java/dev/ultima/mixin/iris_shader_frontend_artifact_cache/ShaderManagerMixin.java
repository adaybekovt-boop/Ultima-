package dev.ultima.mixin.iris_shader_frontend_artifact_cache;

import dev.ultima.client.iris.cache.IrisFrontendArtifactCache;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"))
    private void ultima$clearArtifactRequestState(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        IrisFrontendArtifactCache.beginReload();
    }

    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN"))
    private void ultima$finishArtifactReloadTiming(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        IrisFrontendArtifactCache.endReload();
    }
}
