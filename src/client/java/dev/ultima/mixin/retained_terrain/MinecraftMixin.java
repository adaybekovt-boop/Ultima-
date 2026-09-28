package dev.ultima.mixin.retained_terrain;

import dev.ultima.client.renderer.retained.RetainedTerrainRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops Ultima-owned GPU tables when the client leaves a world. Vanilla mesh buffers are only
 * referenced and are not closed here. {@code LevelRenderer.close} still resets on full teardown.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ultimaRetainedSetLevel(final ClientLevel level, final CallbackInfo ci) {
        RetainedTerrainRenderer.get().reset();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void ultimaRetainedDisconnect(
            final Screen screen, final boolean keepResourcePacks, final boolean stopSound, final CallbackInfo ci) {
        RetainedTerrainRenderer.get().reset();
    }
}
