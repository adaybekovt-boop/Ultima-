package dev.ultima.mixin.temporal;

import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.temporal.TemporalResetReason;
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
            at = @At("RETURN"))
    private void ultimaTemporalResourceReload(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        TemporalPipeline.get().resetHistory(TemporalResetReason.RESOURCE_RELOAD);
    }
}
