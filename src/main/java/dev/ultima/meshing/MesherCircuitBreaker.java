package dev.ultima.meshing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session-scoped circuit breaker keyed by BlockState / model identity, not by
 * section. One throwing cube model is excluded from the fast path after
 * {@link #TRIP_AFTER} failures; other blocks in the same section keep using
 * the fast path. A tripped key stays open until {@link #reset()} (world
 * unload / test), so a bad model cannot spam exceptions every compile.
 *
 * <p>This is not a section blacklist. A single noisy model must not degrade
 * the whole mesher to vanilla for the rest of the session.
 */
public final class MesherCircuitBreaker {
    public static final int TRIP_AFTER = 3;

    private static final MesherCircuitBreaker INSTANCE = new MesherCircuitBreaker();

    private final ConcurrentHashMap<Object, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Set<Object> open = ConcurrentHashMap.newKeySet();
    private final AtomicInteger trips = new AtomicInteger();
    private final AtomicInteger failureEvents = new AtomicInteger();

    public static MesherCircuitBreaker get() {
        return INSTANCE;
    }

    public boolean isOpen(final Object key) {
        return key != null && this.open.contains(key);
    }

    /**
     * @return {@code true} if this call opened the breaker for {@code key}
     */
    public boolean recordFailure(final Object key) {
        if (key == null || this.open.contains(key)) {
            return false;
        }
        this.failureEvents.incrementAndGet();
        int count = this.failures.computeIfAbsent(key, unused -> new AtomicInteger()).incrementAndGet();
        if (count < TRIP_AFTER) {
            return false;
        }
        if (this.open.add(key)) {
            this.trips.incrementAndGet();
            return true;
        }
        return false;
    }

    public boolean allowFastPath(final Object key) {
        return !this.isOpen(key);
    }

    public int tripCount() {
        return this.trips.get();
    }

    public int failureEvents() {
        return this.failureEvents.get();
    }

    public int openKeys() {
        return this.open.size();
    }

    public int failuresFor(final Object key) {
        AtomicInteger count = this.failures.get(key);
        return count == null ? 0 : count.get();
    }

    public void reset() {
        this.failures.clear();
        this.open.clear();
        this.trips.set(0);
        this.failureEvents.set(0);
    }
}
