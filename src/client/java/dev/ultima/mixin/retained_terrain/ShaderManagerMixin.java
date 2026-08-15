package dev.ultima.mixin.retained_terrain;

import dev.ultima.client.renderer.retained.RetainedTerrainPipelines;
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
    private void ultimaInvalidateRetainedPipelines(
            final ShaderManager.Configs preparations,
            final ResourceManager manager,
            final ProfilerFiller profiler,
            final CallbackInfo ci) {
        RetainedTerrainPipelines.invalidate();
    }
}
