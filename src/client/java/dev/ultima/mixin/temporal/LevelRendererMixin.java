package dev.ultima.mixin.temporal;

import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.temporal.TemporalResetReason;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void ultimaTemporalRendererClose(final CallbackInfo ci) {
        TemporalPipeline.get().resetHistory(TemporalResetReason.RENDERER_REINIT);
    }
}
