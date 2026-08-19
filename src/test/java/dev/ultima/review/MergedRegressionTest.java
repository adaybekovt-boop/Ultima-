package dev.ultima.review;

import dev.ultima.cache.StatePropertyCacheEquivalenceTest;
import dev.ultima.cache.TagBitsetEquivalenceTest;
import dev.ultima.recipe.RecipeMatchCacheTest;
import dev.ultima.sleeping.HopperSleepEquivalenceTest;

/**
 * Final integration checkpoint. Every branch-local regression suite that must
 * survive the multi-branch merge is invoked from this single entrypoint.
 */
public final class MergedRegressionTest {
    private MergedRegressionTest() {
    }

    public static void main(final String[] args) {
        ForensicRegressionTest.main(args);
        VanillaClientHostingChecks.run();
        FsrUpscalingChecks.run();
        SettingsScreenLogicTest.run();
        ServerTelemetryChecks.run();
        HopperSleepEquivalenceTest.run();
        RecipeMatchCacheTest.main(new String[0]);
        TagBitsetEquivalenceTest.run();
        StatePropertyCacheEquivalenceTest.run();
        SlotMaskEntityQueryTest.run();
        System.out.println("Merged integration regression checks passed.");
    }
}
