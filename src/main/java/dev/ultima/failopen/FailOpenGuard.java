package dev.ultima.failopen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared fail-open ("door") for second-wave simulation modules.
 *
 * <p>Mirrors {@link dev.ultima.sleeping.HopperSleepFailOpen} and
 * {@link dev.ultima.meshing.MesherSectionFailOpen}: an unexpected fault in an Ultima
 * optimization must log WARN, skip that one case, run honest vanilla, and leave the
 * server tick alive. Other cases keep using the optimization.
 *
 * <p>A lightweight per-case circuit breaker trips after
 * {@link #CIRCUIT_BREAKER_THRESHOLD} consecutive faults for the same case id so a
 * persistently broken recipe type / tag / state / container / query kind stops being
 * retried for the rest of the session.
 */
public final class FailOpenGuard {
    public static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    static final int MAX_TRIPPED_CASES = 256;

    public enum Module {
        RECIPE_MATCH_CACHE("recipe_match_cache", "ultima-recipe-cache"),
        TAG_BITSETS("tag_bitsets", "ultima-tag-bitsets"),
        STATE_PROPERTY_CACHE("state_property_cache", "ultima-state-cache"),
        CONTAINER_SLOT_MASK("container_slot_mask", "ultima-slot-mask"),
        ENTITY_QUERY_EARLY_OUT("entity_query_early_out", "ultima-entity-query");

        private final String key;
        private final Logger logger;
        private final AtomicLong failOpens = new AtomicLong();
        private final ConcurrentHashMap<Object, Integer> consecutive = new ConcurrentHashMap<>();
        private final Set<Object> tripped = ConcurrentHashMap.newKeySet();

        Module(final String key, final String loggerName) {
            this.key = key;
            this.logger = LoggerFactory.getLogger(loggerName);
        }

        public String key() {
            return this.key;
        }

        Logger logger() {
            return this.logger;
        }

        void resetForTests() {
            this.failOpens.set(0L);
            this.consecutive.clear();
            this.tripped.clear();
        }
    }

    private static volatile Module testFaultModule;
    private static volatile Object testFaultCase;

    private FailOpenGuard() {
    }

    public static void armTestFault(final Module module, final Object caseId) {
        testFaultModule = module;
        testFaultCase = caseId;
    }

    public static void clearTestFault() {
        testFaultModule = null;
        testFaultCase = null;
    }

    public static boolean testFaultArmed() {
        return testFaultModule != null && testFaultCase != null;
    }

    public static void maybeThrowForTest(final Module module, final Object caseId) {
        Module armedModule = testFaultModule;
        Object armedCase = testFaultCase;
        if (armedModule == module && armedCase != null && armedCase.equals(caseId)) {
            testFaultModule = null;
            testFaultCase = null;
            throw new IllegalStateException("ultima " + module.key() + " test fault for " + caseId);
        }
    }

    public static void resetForTests() {
        clearTestFault();
        for (Module module : Module.values()) {
            module.resetForTests();
        }
    }

    public static long failOpenCount(final Module module) {
        return module.failOpens.get();
    }

    public static boolean isTripped(final Module module, final Object caseId) {
        return caseId != null && module.tripped.contains(caseId);
    }

    /**
     * Run {@code optimized}; on fault or a tripped case, run {@code vanilla} instead.
     * The vanilla result is never written back by this method.
     */
    public static <T> T supply(
            final Module module, final Object caseId, final Supplier<T> optimized, final Supplier<T> vanilla) {
        if (isTripped(module, caseId)) {
            return vanilla.get();
        }
        try {
            maybeThrowForTest(module, caseId);
            T value = optimized.get();
            recordSuccess(module, caseId);
            return value;
        } catch (Throwable error) {
            failOpen(module, caseId, error);
            return vanilla.get();
        }
    }

    /**
     * Run {@code optimized}; on fault or a tripped case return {@code null} so the caller
     * can take the vanilla path. Used by Mixin HEAD cache hits.
     */
    public static <T> T callNullable(final Module module, final Object caseId, final Supplier<T> optimized) {
        if (isTripped(module, caseId)) {
            return null;
        }
        try {
            maybeThrowForTest(module, caseId);
            T value = optimized.get();
            recordSuccess(module, caseId);
            return value;
        } catch (Throwable error) {
            failOpen(module, caseId, error);
            return null;
        }
    }

    /**
     * Boolean decision helper. {@code onFault} is the safe vanilla choice
     * ({@code false} = do not skip work / do not early-out).
     */
    public static boolean test(
            final Module module,
            final Object caseId,
            final BooleanSupplier optimized,
            final boolean onFault) {
        if (isTripped(module, caseId)) {
            return onFault;
        }
        try {
            maybeThrowForTest(module, caseId);
            boolean value = optimized.getAsBoolean();
            recordSuccess(module, caseId);
            return value;
        } catch (Throwable error) {
            failOpen(module, caseId, error);
            return onFault;
        }
    }

    public static void run(final Module module, final Object caseId, final Runnable action) {
        if (isTripped(module, caseId)) {
            return;
        }
        try {
            maybeThrowForTest(module, caseId);
            action.run();
            recordSuccess(module, caseId);
        } catch (Throwable error) {
            failOpen(module, caseId, error);
        }
    }

    public static void failOpen(final Module module, final Object caseId, final Throwable error) {
        module.logger()
                .warn(
                        "{} failed open to vanilla for {} ({})",
                        module.key(),
                        caseId == null ? "unknown" : caseId,
                        error == null ? "unknown" : error.toString(),
                        error);
        recordFailure(module, caseId);
    }

    private static void recordSuccess(final Module module, final Object caseId) {
        if (caseId != null) {
            module.consecutive.remove(caseId);
        }
    }

    private static void recordFailure(final Module module, final Object caseId) {
        module.failOpens.incrementAndGet();
        if (caseId == null) {
            return;
        }
        int consecutive = module.consecutive.merge(caseId, 1, Integer::sum);
        if (consecutive >= CIRCUIT_BREAKER_THRESHOLD && module.tripped.size() < MAX_TRIPPED_CASES) {
            module.tripped.add(caseId);
        }
    }
}
