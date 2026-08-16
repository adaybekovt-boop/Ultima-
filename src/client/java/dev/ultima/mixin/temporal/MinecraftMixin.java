package dev.ultima.mixin.temporal;

import dev.ultima.client.temporal.TemporalPipeline;
import dev.ultima.temporal.TemporalResetReason;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    public ClientLevel level;

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ultimaTemporalSetLevel(final ClientLevel level, final CallbackInfo ci) {
        TemporalResetReason reason = this.level == null
                ? TemporalResetReason.WORLD_LOAD
                : TemporalResetReason.DIMENSION_CHANGE;
        TemporalPipeline.get().resetHistory(reason);
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void ultimaTemporalDisconnect(
            final Screen screen,
            final boolean keepResourcePacks,
            final boolean stopSound,
            final CallbackInfo ci) {
        TemporalPipeline.get().resetHistory(TemporalResetReason.WORLD_UNLOAD);
    }
}
