package dev.ultima.mixin.state_property_cache;

import dev.ultima.cache.state.StatePropertyRuntime;
import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PathType, WATER pathfindability, and FIRE-tag danger bits can change when tags rebind.
 */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin {
    @Inject(method = "refreshTagsInHolders", at = @At("HEAD"))
    private void ultimaInvalidateStateProperties(final CallbackInfo ci) {
        try {
            StatePropertyRuntime.invalidateAll("refresh_tags_in_holders");
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.STATE_PROPERTY_CACHE, "refresh_tags_in_holders", error);
        }
    }
}
