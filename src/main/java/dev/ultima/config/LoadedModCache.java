package dev.ultima.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Process-wide cache of {@link FabricLoader#isModLoaded(String)}. The loaded-mod set
 * cannot change after launch, so every production caller must go through this cache
 * instead of probing FabricLoader on a hot path.
 *
 * <p>Tests install a counting probe via {@link #runWithProbeForTest} so a future
 * regression that drops the cache and re-probes every call is a behavioral failure,
 * not a source-text grep.
 */
public final class LoadedModCache {
    @FunctionalInterface
    public interface Probe {
        boolean isModLoaded(String modId);
    }

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger PROBE_CALLS = new AtomicInteger();
    private static volatile Probe probe = LoadedModCache::fabricProbe;

    private LoadedModCache() {
    }

    public static boolean isLoaded(final String modId) {
        return CACHE.computeIfAbsent(modId, id -> {
            PROBE_CALLS.incrementAndGet();
            return probe.isModLoaded(id);
        });
    }

    public static int probeCallCount() {
        return PROBE_CALLS.get();
    }

    /**
     * Tests only. Replaces the FabricLoader backend and clears the cache for the
     * duration of {@code action}, then restores both.
     */
    public static void runWithProbeForTest(final Probe testProbe, final Runnable action) {
        Probe previous = probe;
        int previousCount = PROBE_CALLS.get();
        ConcurrentHashMap<String, Boolean> previousCache = new ConcurrentHashMap<>(CACHE);
        probe = testProbe;
        CACHE.clear();
        PROBE_CALLS.set(0);
        try {
            action.run();
        } finally {
            probe = previous;
            CACHE.clear();
            CACHE.putAll(previousCache);
            PROBE_CALLS.set(previousCount);
        }
    }

    private static boolean fabricProbe(final String modId) {
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
