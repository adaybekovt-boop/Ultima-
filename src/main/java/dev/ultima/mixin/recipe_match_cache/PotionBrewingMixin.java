package dev.ultima.mixin.recipe_match_cache;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.recipe.BrewingFirstMatchCache;
import dev.ultima.recipe.RecipeCacheDoors;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * First-match cache for brewing stand mix queries. Fuel/brew-time are not part of the key.
 *
 * <p>Fail-open is {@link RecipeCacheDoors} ({@code callNullable}/{@code run}). Do not add a
 * second {@code catch}/{@code failOpen()} around those calls.
 */
@Mixin(PotionBrewing.class)
public abstract class PotionBrewingMixin {
    @Unique
    private final BrewingFirstMatchCache ultimaBrewingCache = new BrewingFirstMatchCache();

    @WrapMethod(method = "isIngredient")
    private boolean ultimaIsIngredient(final ItemStack ingredient, final Operation<Boolean> original) {
        return RecipeCacheDoors.wrapIsIngredient(
                this.ultimaBrewingCache, ingredient, () -> original.call(ingredient));
    }

    @WrapMethod(method = "hasMix")
    private boolean ultimaHasMix(
            final ItemStack source, final ItemStack ingredient, final Operation<Boolean> original) {
        return RecipeCacheDoors.wrapHasMix(
                this.ultimaBrewingCache, source, ingredient, () -> original.call(source, ingredient));
    }

    @WrapMethod(method = "mix")
    private ItemStack ultimaMix(
            final ItemStack ingredient, final ItemStack source, final Operation<ItemStack> original) {
        return RecipeCacheDoors.wrapMix(
                this.ultimaBrewingCache, ingredient, source, () -> original.call(ingredient, source));
    }
}
