package dev.ultima.recipe;

import dev.ultima.failopen.FailOpenGuard;
import java.util.Optional;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@code RecipeManager} first-match store. The Mixins keep one instance next to the live
 * {@code RecipeMap} and rebuild the policy whenever vanilla replaces that map.
 *
 * <p>Licensing: FastWorkbench / FastFurnace / FastSuite (Shadows-of-Fire) are MIT-licensed as of
 * their 26.1 branches. This class is a clean-room implementation of the <em>idea</em> of remembering
 * the first vanilla match; it does not contain their code. Unlike those mods, a cache hit never
 * returns "any matching recipe" or "the last recipe that happened to match a different input".
 */
public final class RecipeFirstMatchCache {
    private final FirstMatchTable<Object, Optional<RecipeHolder<?>>> table = new FirstMatchTable<>();
    private RecipeCachePolicy policy = RecipeCachePolicy.EMPTY;

    public void onRecipesReplaced(final RecipeCachePolicy policy) {
        this.policy = policy;
        this.table.invalidate();
        RecipeMatchTelemetry.invalidation();
    }

    /**
     * @return cached first-match, or {@code null} if the caller must run vanilla
     */
    public @Nullable Optional<RecipeHolder<?>> lookup(
            final RecipeType<?> type, final RecipeInput input, final boolean recordTelemetry) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, () -> this.lookupUnchecked(type, input, recordTelemetry));
    }

    public void store(final RecipeType<?> type, final RecipeInput input, final Optional<? extends RecipeHolder<?>> result) {
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, () -> this.storeUnchecked(type, input, result));
    }

    /**
     * Hinted lookups must still prefer the hint when it matches. If the cached first-match is that
     * same holder, skipping {@code hint.matches()} is equivalent for proven-pure recipes.
     */
    public @Nullable Optional<RecipeHolder<?>> hintedHit(
            final RecipeType<?> type, final RecipeInput input, final RecipeHolder<?> hint) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, () -> this.hintedHitUnchecked(type, input, hint));
    }

    /**
     * Cache probe without a fail-open door. Mixins wrap this with
     * {@link FailOpenGuard#callNullable}; do not nest another door around {@link #lookup}.
     */
    public @Nullable Optional<RecipeHolder<?>> lookupUnchecked(
            final RecipeType<?> type, final RecipeInput input, final boolean recordTelemetry) {
        if (input.isEmpty()) {
            return Optional.empty();
        }
        if (this.policy.shouldBypassCache(type, input)) {
            recordFallback(input, recordTelemetry);
            return null;
        }
        Object key = RecipeMatchKeys.keyFor(type, input);
        if (key == null) {
            recordFallback(input, recordTelemetry);
            return null;
        }
        if (!this.table.contains(key)) {
            recordMiss(input, recordTelemetry);
            return null;
        }
        Optional<RecipeHolder<?>> hit = this.table.get(key);
        recordHit(input, recordTelemetry);
        return hit;
    }

    /**
     * Cache store without a fail-open door. Mixins wrap this with {@link FailOpenGuard#run}.
     */
    public void storeUnchecked(
            final RecipeType<?> type, final RecipeInput input, final Optional<? extends RecipeHolder<?>> result) {
        if (input.isEmpty() || this.policy.shouldBypassCache(type, input)) {
            return;
        }
        Object key = RecipeMatchKeys.keyFor(type, input);
        if (key == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Optional<RecipeHolder<?>> stored = (Optional<RecipeHolder<?>>) (Optional<?>) result;
        this.table.put(key, stored);
    }

    /**
     * Hinted probe without a fail-open door. Mixins wrap this with
     * {@link FailOpenGuard#callNullable}.
     */
    public @Nullable Optional<RecipeHolder<?>> hintedHitUnchecked(
            final RecipeType<?> type, final RecipeInput input, final RecipeHolder<?> hint) {
        Optional<RecipeHolder<?>> first = this.lookupUnchecked(type, input, false);
        if (first == null) {
            return null;
        }
        if (first.isPresent() && first.get().id().equals(hint.id())) {
            recordHit(input, true);
            return first;
        }
        return null;
    }

    private static void recordHit(final RecipeInput input, final boolean recordTelemetry) {
        if (!recordTelemetry) {
            return;
        }
        if (input instanceof SingleRecipeInput) {
            RecipeMatchTelemetry.furnaceHit();
        } else if (input instanceof CraftingInput) {
            RecipeMatchTelemetry.craftingHit();
        }
    }

    private static void recordMiss(final RecipeInput input, final boolean recordTelemetry) {
        if (!recordTelemetry) {
            return;
        }
        if (input instanceof SingleRecipeInput) {
            RecipeMatchTelemetry.furnaceMiss();
        } else if (input instanceof CraftingInput) {
            RecipeMatchTelemetry.craftingMiss();
        }
    }

    private static void recordFallback(final RecipeInput input, final boolean recordTelemetry) {
        if (!recordTelemetry) {
            return;
        }
        if (input instanceof SingleRecipeInput) {
            RecipeMatchTelemetry.furnaceFallback();
        } else if (input instanceof CraftingInput) {
            RecipeMatchTelemetry.craftingFallback();
        }
    }
}
