package dev.ultima.mixin.tag_bitsets;

import dev.ultima.cache.tags.TagBitsetRuntime;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Membership is a boolean; order of tag contents is not observed. Unknown tags, out-of-range ids,
 * Direct holders, and unbound references fall through to vanilla {@code boundTags().contains}.
 */
@Mixin(Holder.Reference.class)
public abstract class HolderReferenceMixin<T> {
    @Shadow
    @Final
    private HolderOwner<T> owner;

    @Shadow
    public abstract boolean isBound();

    @Shadow
    public abstract T value();

    @Inject(method = "is(Lnet/minecraft/tags/TagKey;)Z", at = @At("HEAD"), cancellable = true)
    private void ultimaTagBitset(final TagKey<T> tag, final CallbackInfoReturnable<Boolean> cir) {
        if (!this.isBound() || !(this.owner instanceof Registry<?> registry)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Registry<T> typed = (Registry<T>)registry;
        Boolean hit = TagBitsetRuntime.lookup(typed.key(), tag, typed.getId(this.value()));
        if (hit != null) {
            cir.setReturnValue(hit);
        }
    }
}
