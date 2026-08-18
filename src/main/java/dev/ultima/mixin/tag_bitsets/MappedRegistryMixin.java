package dev.ultima.mixin.tag_bitsets;

import dev.ultima.cache.tags.TagBitsetRuntime;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop the bitset snapshot before holders receive rebound tag sets so a pathfinding worker cannot
 * observe stale membership against the new {@code boundTags} map.
 */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin {
    @Inject(method = "refreshTagsInHolders", at = @At("HEAD"))
    private void ultimaDropTagBitsets(final CallbackInfo ci) {
        TagBitsetRuntime.drop("refresh_tags_in_holders");
    }
}
