package dev.ultima.mixin.client_benchmark;

import dev.ultima.client.benchmark.ClientFrameBenchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void ultimaBeginMeasuredFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft)(Object)this;
        ClientFrameBenchmark.beginFrame(minecraft.level != null && minecraft.isGameLoadFinished());
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void ultimaEndMeasuredFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        ClientFrameBenchmark.endFrame();
    }
}
