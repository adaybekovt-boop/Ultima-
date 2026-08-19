package dev.ultima.mixin.recipe_match_cache;

import dev.ultima.recipe.BrewingFirstMatchCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * First-match cache for brewing stand mix queries. Fuel/brew-time are not part of the key.
 */
@Mixin(PotionBrewing.class)
public abstract class PotionBrewingMixin {
    @Unique
    private final BrewingFirstMatchCache ultimaBrewingCache = new BrewingFirstMatchCache();

    @Inject(method = "isIngredient", at = @At("HEAD"), cancellable = true)
    private void ultimaCachedIsIngredient(final ItemStack ingredient, final CallbackInfoReturnable<Boolean> cir) {
        Boolean cached = this.ultimaBrewingCache.lookupIngredient(ingredient);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "isIngredient", at = @At("RETURN"))
    private void ultimaStoreIsIngredient(final ItemStack ingredient, final CallbackInfoReturnable<Boolean> cir) {
        Boolean result = cir.getReturnValue();
        if (result != null) {
            this.ultimaBrewingCache.storeIngredient(ingredient, result);
        }
    }

    @Inject(method = "hasMix", at = @At("HEAD"), cancellable = true)
    private void ultimaCachedHasMix(final ItemStack source, final ItemStack ingredient, final CallbackInfoReturnable<Boolean> cir) {
        Boolean cached = this.ultimaBrewingCache.lookupHasMix(source, ingredient);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "hasMix", at = @At("RETURN"))
    private void ultimaStoreHasMix(final ItemStack source, final ItemStack ingredient, final CallbackInfoReturnable<Boolean> cir) {
        Boolean result = cir.getReturnValue();
        if (result != null) {
            this.ultimaBrewingCache.storeHasMix(source, ingredient, result);
        }
    }

    @Inject(method = "mix", at = @At("HEAD"), cancellable = true)
    private void ultimaCachedMix(final ItemStack ingredient, final ItemStack source, final CallbackInfoReturnable<ItemStack> cir) {
        BrewingFirstMatchCache.MixDecision cached = this.ultimaBrewingCache.lookupMix(source, ingredient);
        if (cached != null) {
            cir.setReturnValue(cached.apply(source));
        }
    }

    @Inject(method = "mix", at = @At("RETURN"))
    private void ultimaStoreMix(final ItemStack ingredient, final ItemStack source, final CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = cir.getReturnValue();
        if (result != null) {
            this.ultimaBrewingCache.storeMix(source, ingredient, result);
        }
    }
}
