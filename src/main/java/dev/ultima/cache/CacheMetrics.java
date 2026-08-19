package dev.ultima.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolated counters for membership/property lookups. These are not FPS claims.
 */
public final class CacheMetrics {
    public final AtomicLong hits = new AtomicLong();
    public final AtomicLong misses = new AtomicLong();
    public final AtomicLong fallbacks = new AtomicLong();
    public final AtomicLong rebuilds = new AtomicLong();
    public final AtomicLong invalidations = new AtomicLong();

    public void reset() {
        this.hits.set(0L);
        this.misses.set(0L);
        this.fallbacks.set(0L);
        this.rebuilds.set(0L);
        this.invalidations.set(0L);
    }
}
