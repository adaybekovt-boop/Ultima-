package dev.ultima.mixin.client_benchmark;

import com.mojang.blaze3d.systems.TimerQuery;
import dev.ultima.client.benchmark.ClientFrameBenchmark;
import dev.ultima.client.broker.GpuQuerySample;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow @Final private TimerQuery timerQuery;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void ultimaBeginMeasuredFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft)(Object)this;
        ClientFrameBenchmark.beginFrame(minecraft.level != null && minecraft.isGameLoadFinished());
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void ultimaEndMeasuredFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft)(Object)this;
        long gpuNanos = GpuQuerySample.nanosForController(this.timerQuery);
        ClientFrameBenchmark.endFrame(minecraft.getFrameTimeNs(), gpuNanos > 0L ? gpuNanos : -1L);
    }
}
