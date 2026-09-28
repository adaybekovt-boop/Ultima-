package dev.ultima.mixin.render_warmup_system;

import dev.ultima.client.warmup.FirstUseProfiler;
import dev.ultima.client.warmup.WarmupCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void ultima$warmupFrameStart(final boolean advanceGameTime, final CallbackInfo ci) {
        FirstUseProfiler.beginFrame();
        WarmupCoordinator.pump();
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void ultima$warmupFrameEnd(final boolean advanceGameTime, final CallbackInfo ci) {
        FirstUseProfiler.endFrame();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void ultima$warmupDisconnect(
            final Screen screen,
            final boolean keepResourcePacks,
            final boolean stopSound,
            final CallbackInfo ci) {
        WarmupCoordinator.cancel("disconnect");
    }
}
