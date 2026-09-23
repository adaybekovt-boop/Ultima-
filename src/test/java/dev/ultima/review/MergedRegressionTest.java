package dev.ultima.review;

import dev.ultima.broker.AdmissionControllerTest;
import dev.ultima.config.RendererFamilyEvidenceTest;
import dev.ultima.cache.StatePropertyCacheEquivalenceTest;
import dev.ultima.cache.TagBitsetEquivalenceTest;
import dev.ultima.cache.iris.ArtifactCacheStoreTest;
import dev.ultima.client.benchmark.ReplayTimelineTest;
import dev.ultima.client.broker.BrokerMetricsTest;
import dev.ultima.client.diagnostics.KillerModuleDiagnosticsTest;
import dev.ultima.client.iris.cache.IrisTransformKeyEncoderTest;
import dev.ultima.recipe.RecipeMatchCacheTest;
import dev.ultima.server.metrics.ServerMetricsPhaseTest;
import dev.ultima.sleeping.HopperSleepEquivalenceTest;
import dev.ultima.warmup.BudgetedWarmupPlanTest;

/**
 * Final integration checkpoint. Every branch-local regression suite that must
 * survive the multi-branch merge is invoked from this single entrypoint.
 */
public final class MergedRegressionTest {
    private MergedRegressionTest() {
    }

    public static void main(final String[] args) throws Exception {
        dev.ultima.failopen.Wave2FailOpenTest.run();
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
        MergedModuleContractTest.run();
        CommandCompactionRecoveryTest.main(args);
        ReplayTimelineTest.main(args);
        ArtifactCacheStoreTest.main(args);
        IrisTransformKeyEncoderTest.main(args);
        AdmissionControllerTest.main(args);
        BrokerMetricsTest.main(args);
        BudgetedWarmupPlanTest.main(args);
        KillerModuleDiagnosticsTest.main(args);
        RendererFamilyEvidenceTest.run();
        ServerMetricsPhaseTest.run();
        MixinBytecodeChecks.run();
        System.out.println("Merged integration regression checks passed.");
    }
}
