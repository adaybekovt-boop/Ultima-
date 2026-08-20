package dev.ultima.recipe;

import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * First-match store for {@code PotionBrewing}. Vanilla walks {@code containerMixes} then
 * {@code potionMixes} and returns the first mix that applies. The cache stores that same decision
 * keyed by source identity+components and ingredient identity+components.
 *
 * <p>A cached "no mix" returns the caller's {@code source} reference, matching vanilla. A cached
 * mix returns a fresh {@link ItemStack#copy()} so the brewing stand cannot mutate the table.
 */
public final class BrewingFirstMatchCache {
    private final FirstMatchTable<RecipeMatchKeys.BrewingKey, Object> table = new FirstMatchTable<>();

    public void invalidate() {
        FailOpenGuard.run(FailOpenGuard.Module.RECIPE_MATCH_CACHE, "brewing-reload", () -> {
            this.table.invalidate();
            RecipeMatchTelemetry.invalidation();
        });
    }

    public boolean isEmpty() {
        return this.table.isEmpty();
    }

    public @Nullable Boolean lookupIngredient(final ItemStack ingredient) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.INGREDIENT,
                () -> this.lookupBoolean(RecipeMatchKeys.BrewingKey.ingredient(ingredient)));
    }

    public void storeIngredient(final ItemStack ingredient, final boolean result) {
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.INGREDIENT,
                () -> this.table.put(RecipeMatchKeys.BrewingKey.ingredient(ingredient), result));
    }

    public @Nullable Boolean lookupHasMix(final ItemStack source, final ItemStack ingredient) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.HAS_MIX,
                () -> this.lookupBoolean(
                        RecipeMatchKeys.BrewingKey.pair(RecipeMatchKeys.BrewingKey.Kind.HAS_MIX, source, ingredient)));
    }

    public void storeHasMix(final ItemStack source, final ItemStack ingredient, final boolean result) {
        FailOpenGuard.run(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.HAS_MIX,
                () -> this.table.put(
                        RecipeMatchKeys.BrewingKey.pair(RecipeMatchKeys.BrewingKey.Kind.HAS_MIX, source, ingredient),
                        result));
    }

    public @Nullable MixDecision lookupMix(final ItemStack source, final ItemStack ingredient) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.RECIPE_MATCH_CACHE,
                RecipeMatchKeys.BrewingKey.Kind.MIX,
                () -> {
                    RecipeMatchKeys.BrewingKey key =
                            RecipeMatchKeys.BrewingKey.pair(RecipeMatchKeys.BrewingKey.Kind.MIX, source, ingredient);
                    if (!this.table.contains(key)) {
                        RecipeMatchTelemetry.brewingMiss();
                        return null;
                    }
                    RecipeMatchTelemetry.brewingHit();
                    return (MixDecision) this.table.get(key);
                });
    }

    public void storeMix(final ItemStack source, final ItemStack ingredient, final ItemStack result) {
        FailOpenGuard.run(FailOpenGuard.Module.RECIPE_MATCH_CACHE, RecipeMatchKeys.BrewingKey.Kind.MIX, () -> {
            MixDecision decision = result == source ? MixDecision.UNCHANGED : MixDecision.changed(result.copy());
            this.table.put(
                    RecipeMatchKeys.BrewingKey.pair(RecipeMatchKeys.BrewingKey.Kind.MIX, source, ingredient), decision);
        });
    }

    private @Nullable Boolean lookupBoolean(final RecipeMatchKeys.BrewingKey key) {
        if (!this.table.contains(key)) {
            RecipeMatchTelemetry.brewingMiss();
            return null;
        }
        RecipeMatchTelemetry.brewingHit();
        return (Boolean) this.table.get(key);
    }

    public record MixDecision(boolean unchanged, @Nullable ItemStack output) {
        public static final MixDecision UNCHANGED = new MixDecision(true, null);

        public static MixDecision changed(final ItemStack output) {
            return new MixDecision(false, output);
        }

        public ItemStack apply(final ItemStack source) {
            if (this.unchanged || this.output == null) {
                return source;
            }
            return this.output.copy();
        }
    }
}
