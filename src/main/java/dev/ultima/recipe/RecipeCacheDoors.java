package dev.ultima.recipe;

import dev.ultima.failopen.FailOpenGuard;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import org.jspecify.annotations.Nullable;

/**
 * Production Mixin bodies for {@code recipe_match_cache}.
 *
 * <p>{@code RecipeManagerMixin} / {@code PotionBrewingMixin} call these methods so fail-open
 * goes through {@link FailOpenGuard#callNullable} / {@link FailOpenGuard#run} (isTripped,
 * consecutive success reset, one WARN per fault). Tests must call this class, not a parallel
 * reimplementation. Do not wrap these methods in a second door.
 */
public final class RecipeCacheDoors {
    private RecipeCacheDoors() {
    }

    public static void onRecipesReplaced(final RecipeFirstMatchCache cache, final RecipeMap recipes) {
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                "reload",
                () -> cache.onRecipesReplaced(RecipeCachePolicy.inspect(recipes)));
    }

    /**
     * {@code RecipeManager.getRecipeFor(type, input, level)} wrap: HEAD lookup, else vanilla,
     * then RETURN store.
     */
    public static Optional<RecipeHolder<?>> wrapGetRecipeFor(
            final RecipeFirstMatchCache cache,
            final RecipeType<?> type,
            final RecipeInput input,
            final Supplier<Optional<RecipeHolder<?>>> vanilla) {
        Optional<RecipeHolder<?>> cached = FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                type,
                () -> cache.lookupUnchecked(type, input, true));
        if (cached != null) {
            return cached;
        }
        Optional<RecipeHolder<?>> scanned = vanilla.get();
        if (scanned != null) {
            FailOpenGuard.run(
                    FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, () -> cache.storeUnchecked(type, input, scanned));
        }
        return scanned;
    }

    /**
     * Hinted {@code getRecipeFor} wrap. Vanilla still prefers a matching hint; a cache hit is
     * only returned when it is that same holder.
     */
    public static Optional<RecipeHolder<?>> wrapHintedGetRecipeFor(
            final RecipeFirstMatchCache cache,
            final RecipeType<?> type,
            final RecipeInput input,
            final @Nullable RecipeHolder<?> recipeHint,
            final Supplier<Optional<RecipeHolder<?>>> vanilla) {
        if (recipeHint != null) {
            Optional<RecipeHolder<?>> cached = FailOpenGuard.callNullable(
                    FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                    type,
                    () -> cache.hintedHitUnchecked(type, input, recipeHint));
            if (cached != null) {
                return cached;
            }
        }
        return vanilla.get();
    }

    public static boolean wrapIsIngredient(
            final BrewingFirstMatchCache cache,
            final ItemStack ingredient,
            final Supplier<Boolean> vanilla) {
        Boolean cached = FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.INGREDIENT,
                () -> cache.lookupIngredientUnchecked(ingredient));
        if (cached != null) {
            return cached;
        }
        boolean scanned = vanilla.get();
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.INGREDIENT,
                () -> cache.storeIngredientUnchecked(ingredient, scanned));
        return scanned;
    }

    public static boolean wrapHasMix(
            final BrewingFirstMatchCache cache,
            final ItemStack source,
            final ItemStack ingredient,
            final Supplier<Boolean> vanilla) {
        Boolean cached = FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.HAS_MIX,
                () -> cache.lookupHasMixUnchecked(source, ingredient));
        if (cached != null) {
            return cached;
        }
        boolean scanned = vanilla.get();
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.HAS_MIX,
                () -> cache.storeHasMixUnchecked(source, ingredient, scanned));
        return scanned;
    }

    public static ItemStack wrapMix(
            final BrewingFirstMatchCache cache,
            final ItemStack ingredient,
            final ItemStack source,
            final Supplier<ItemStack> vanilla) {
        BrewingFirstMatchCache.MixDecision cached = FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.MIX,
                () -> cache.lookupMixUnchecked(source, ingredient));
        if (cached != null) {
            return cached.apply(source);
        }
        ItemStack scanned = vanilla.get();
        if (scanned != null) {
            FailOpenGuard.run(
                    FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                    RecipeMatchKeys.BrewingKey.Kind.MIX,
                    () -> cache.storeMixUnchecked(source, ingredient, scanned));
        }
        return scanned;
    }
}
