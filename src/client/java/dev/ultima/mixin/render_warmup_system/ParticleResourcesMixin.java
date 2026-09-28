package dev.ultima.mixin.render_warmup_system;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.client.warmup.FirstUseProfiler;
import net.minecraft.client.particle.ParticleResources;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ParticleResources.class)
public abstract class ParticleResourcesMixin {
    @WrapMethod(method = "registerProviders")
    private void ultima$profileProviderInitialization(final Operation<Void> original) {
        FirstUseProfiler.Token token = FirstUseProfiler.begin("particle", "provider_registration");
        try {
            original.call();
        } finally {
            FirstUseProfiler.end(token);
        }
    }
}
