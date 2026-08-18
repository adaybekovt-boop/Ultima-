package dev.ultima.sleeping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local counters for hopper sleeping. Keys match the reserved {@code be.*} telemetry names
 * so a later server-metrics module can scrape {@link #snapshot()} without changing producers.
 */
public final class BlockEntitySleepMetrics {
    private static final AtomicInteger SLEEPING = new AtomicInteger();
    private static final AtomicLong SLEEP_ENTERS = new AtomicLong();
    private static final AtomicLong WAKEUPS = new AtomicLong();
    private static final AtomicLong TICKS_SKIPPED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong THRASH_TRIPS = new AtomicLong();

    private BlockEntitySleepMetrics() {
    }

    public static void onSleepEnter() {
        SLEEPING.incrementAndGet();
        SLEEP_ENTERS.incrementAndGet();
    }

    public static void onWake() {
        SLEEPING.updateAndGet(current -> Math.max(0, current - 1));
        WAKEUPS.incrementAndGet();
    }

    public static void onTickSkipped() {
        TICKS_SKIPPED.incrementAndGet();
    }

    public static void onRefused() {
        REFUSED.incrementAndGet();
    }

    public static void onThrashTrip() {
        THRASH_TRIPS.incrementAndGet();
    }

    public static int sleeping() {
        return SLEEPING.get();
    }

    public static long wakeups() {
        return WAKEUPS.get();
    }

    public static long ticksSkipped() {
        return TICKS_SKIPPED.get();
    }

    public static long refused() {
        return REFUSED.get();
    }

    public static long thrashTrips() {
        return THRASH_TRIPS.get();
    }

    public static Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("be.sleeping", (long)SLEEPING.get());
        values.put("be.sleep_enters", SLEEP_ENTERS.get());
        values.put("be.wakeups", WAKEUPS.get());
        values.put("be.ticks_skipped", TICKS_SKIPPED.get());
        values.put("be.refused", REFUSED.get());
        values.put("be.thrashes", THRASH_TRIPS.get());
        return Map.copyOf(values);
    }

    public static void resetForTests() {
        SLEEPING.set(0);
        SLEEP_ENTERS.set(0L);
        WAKEUPS.set(0L);
        TICKS_SKIPPED.set(0L);
        REFUSED.set(0L);
        THRASH_TRIPS.set(0L);
    }
}
