package dev.ultima.failopen;

import dev.ultima.config.LoadedModCache;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Deterministic single-thread loop timings for framework overhead on the healthy path.
 * Not an FPS or TPS claim: it isolates per-probe cost of the module gate and the fail-open door.
 *
 * <p>Run with {@code ./gradlew frameworkOverheadMicrobench}. Not part of {@code check}.
 */
public final class FrameworkOverheadMicrobench {
    private static final int WARMUP_ROUNDS = 6;
    private static final int ROUNDS = 9;
    private static final int OPS = 20_000_000;
    private static volatile long sink;

    private record CaseKey(String registry, String path) {
    }

    private FrameworkOverheadMicrobench() {
    }

    public static void main(final String[] args) {
        LoadedModCache.runWithProbeForTest(id -> false, FrameworkOverheadMicrobench::runAll);
    }

    private static void runAll() {
        UltimaConfig config = UltimaConfig.createForTests(Map.of("tag_bitsets", true));
        int index = UltimaModules.indexOf("tag_bitsets");
        report("empty probe (harness floor)", i -> (i & 1) == 0);
        report("config.isEnabled(\"tag_bitsets\")", i -> config.isEnabled("tag_bitsets"));
        report("config.isRuntimeEnabled(index)", i -> config.isRuntimeEnabled(index));

        FailOpenGuard.resetForTests();
        CaseKey[] records = new CaseKey[64];
        Object[] identities = new Object[64];
        for (int i = 0; i < records.length; i++) {
            records[i] = new CaseKey("minecraft:block", "tag_" + i);
            identities[i] = new Object();
        }
        report("callNullable record key, 3-capture lambda", i -> doorRecord(records[i & 63], i, 7L) != null);
        report("callNullable identity key, 1-capture lambda", i -> doorIdentity(identities[i & 63]) != null);
        report("test identity key, 2-capture lambda", i -> doorTest(identities[i & 63], i));
        report("isTripped record key", i -> FailOpenGuard.isTripped(FailOpenGuard.Module.TAG_BITSETS, records[i & 63]));

        FailOpenGuard.noteFailureForTests(FailOpenGuard.Module.TAG_BITSETS, "other-case");
        report("callNullable record key, one unrelated fault recorded",
                i -> doorRecord(records[i & 63], i, 7L) != null);
        FailOpenGuard.resetForTests();
    }

    private static Boolean doorRecord(final CaseKey key, final int rawId, final long salt) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.TAG_BITSETS, key, () -> ((rawId ^ salt) & 1L) == 0L && key != null);
    }

    private static Object doorIdentity(final Object state) {
        return FailOpenGuard.callNullable(FailOpenGuard.Module.STATE_PROPERTY_CACHE, state, () -> state);
    }

    private static boolean doorTest(final Object state, final int rawId) {
        return FailOpenGuard.test(
                FailOpenGuard.Module.ENTITY_QUERY_EARLY_OUT, state, () -> (rawId & 1) == 0 && state != null, false);
    }

    private static void report(final String label, final IntPredicate probe) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            loop(probe);
        }
        double[] nanos = new double[ROUNDS];
        long allocated = 0L;
        for (int round = 0; round < ROUNDS; round++) {
            long bytes = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            loop(probe);
            nanos[round] = (System.nanoTime() - start) / (double) OPS;
            allocated += threads.getThreadAllocatedBytes(thread) - bytes;
        }
        Arrays.sort(nanos);
        System.out.printf("%-58s median=%.3f ns/op min=%.3f max=%.3f alloc=%.3f B/op%n",
                label, nanos[ROUNDS / 2], nanos[0], nanos[ROUNDS - 1], allocated / (double) ROUNDS / OPS);
    }

    private static void loop(final IntPredicate probe) {
        long hits = 0L;
        for (int i = 0; i < OPS; i++) {
            if (probe.test(i)) {
                hits++;
            }
        }
        sink += hits;
    }
}
