package dev.ultima.mixin.mesher_fast_path;

import dev.ultima.meshing.MesherCircuitBreaker;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clears a tripped fast-path model at world and resource boundaries. A reset every frame would
 * re-arm a model that is still failing.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ultimaMesherSetLevel(final ClientLevel level, final CallbackInfo ci) {
        MesherCircuitBreaker.get().reset();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void ultimaMesherDisconnect(
            final Screen screen, final boolean keepResourcePacks, final boolean stopSound, final CallbackInfo ci) {
        MesherCircuitBreaker.get().reset();
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void ultimaMesherReload(final CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        MesherCircuitBreaker.get().reset();
    }
}
