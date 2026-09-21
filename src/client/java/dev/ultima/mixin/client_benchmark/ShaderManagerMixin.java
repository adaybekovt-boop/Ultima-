package dev.ultima.mixin.client_benchmark;

import dev.ultima.client.benchmark.ShaderReloadMetrics;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Symmetric OFF/ON timer for the complete reload, independent of the cache module. */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @Unique private long ultima$benchmarkReloadStartedNanos;

    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("HEAD"))
    private void ultima$beginBenchmarkReload(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        this.ultima$benchmarkReloadStartedNanos = System.nanoTime();
    }

    @Inject(
            method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("RETURN"))
    private void ultima$endBenchmarkReload(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        long started = this.ultima$benchmarkReloadStartedNanos;
        this.ultima$benchmarkReloadStartedNanos = 0L;
        if (started > 0L) {
            ShaderReloadMetrics.record(System.nanoTime() - started);
        }
    }
}
