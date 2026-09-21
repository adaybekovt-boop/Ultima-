package dev.ultima.mixin.cross_pipeline_admission_broker;

import com.mojang.blaze3d.systems.TimerQuery;
import dev.ultima.client.broker.CrossPipelineBroker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
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
    private void ultima$brokerFrameStart(final boolean advanceGameTime, final CallbackInfo ci) {
        CrossPipelineBroker.beginFrame();
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void ultima$brokerFrameEnd(final boolean advanceGameTime, final CallbackInfo ci) {
        Minecraft minecraft = (Minecraft)(Object)this;
        long gpuNanos = this.timerQuery.get();
        CrossPipelineBroker.endFrame(minecraft.getFrameTimeNs(), gpuNanos > 0L ? gpuNanos : -1L);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ultima$brokerWorldChanged(final ClientLevel level, final CallbackInfo ci) {
        CrossPipelineBroker.reset("world_change");
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void ultima$brokerDisconnect(
            final Screen screen,
            final boolean keepResourcePacks,
            final boolean stopSound,
            final CallbackInfo ci) {
        CrossPipelineBroker.reset("disconnect");
    }
}
