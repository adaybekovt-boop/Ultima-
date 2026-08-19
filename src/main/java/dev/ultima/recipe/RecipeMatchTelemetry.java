package dev.ultima.recipe;

/**
 * Process-wide counters for the recipe_match_cache module. Used by Mixins and by the isolated
 * micro-benchmark; not a gameplay API.
 */
public final class RecipeMatchTelemetry {
    private static long craftingHits;
    private static long craftingMisses;
    private static long craftingFallbacks;
    private static long furnaceHits;
    private static long furnaceMisses;
    private static long furnaceFallbacks;
    private static long brewingHits;
    private static long brewingMisses;
    private static long brewingFallbacks;
    private static long invalidations;

    private RecipeMatchTelemetry() {
    }

    public static void reset() {
        craftingHits = 0L;
        craftingMisses = 0L;
        craftingFallbacks = 0L;
        furnaceHits = 0L;
        furnaceMisses = 0L;
        furnaceFallbacks = 0L;
        brewingHits = 0L;
        brewingMisses = 0L;
        brewingFallbacks = 0L;
        invalidations = 0L;
    }

    public static void craftingHit() {
        craftingHits++;
    }

    public static void craftingMiss() {
        craftingMisses++;
    }

    public static void craftingFallback() {
        craftingFallbacks++;
    }

    public static void furnaceHit() {
        furnaceHits++;
    }

    public static void furnaceMiss() {
        furnaceMisses++;
    }

    public static void furnaceFallback() {
        furnaceFallbacks++;
    }

    public static void brewingHit() {
        brewingHits++;
    }

    public static void brewingMiss() {
        brewingMisses++;
    }

    public static void brewingFallback() {
        brewingFallbacks++;
    }

    public static void invalidation() {
        invalidations++;
    }

    public static long craftingHits() {
        return craftingHits;
    }

    public static long craftingMisses() {
        return craftingMisses;
    }

    public static long craftingFallbacks() {
        return craftingFallbacks;
    }

    public static long furnaceHits() {
        return furnaceHits;
    }

    public static long furnaceMisses() {
        return furnaceMisses;
    }

    public static long furnaceFallbacks() {
        return furnaceFallbacks;
    }

    public static long brewingHits() {
        return brewingHits;
    }

    public static long brewingMisses() {
        return brewingMisses;
    }

    public static long brewingFallbacks() {
        return brewingFallbacks;
    }

    public static long invalidations() {
        return invalidations;
    }
}
