package dev.ultima.visibility;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Incremental visibility counters for later camera-path A/B. Not a performance
 * claim.
 */
public final class VisibilityMetrics {
    private static final AtomicLong CPU_NS = new AtomicLong();
    private static final AtomicInteger SECTIONS_CONSIDERED = new AtomicInteger();
    private static final AtomicInteger SECTIONS_VISIBLE = new AtomicInteger();
    private static final AtomicInteger STATE_CHANGES = new AtomicInteger();
    private static final AtomicInteger FULL_REBUILDS = new AtomicInteger();
    private static final AtomicInteger INCREMENTAL_UPDATES = new AtomicInteger();

    private VisibilityMetrics() {
    }

    public static void reset() {
        CPU_NS.set(0L);
        SECTIONS_CONSIDERED.set(0);
        SECTIONS_VISIBLE.set(0);
        STATE_CHANGES.set(0);
        FULL_REBUILDS.set(0);
        INCREMENTAL_UPDATES.set(0);
    }

    public static void record(
            final long visibilityCpuNs,
            final int sectionsConsidered,
            final int sectionsVisible,
            final int visibilityStateChanges,
            final int fullRebuilds,
            final int incrementalUpdates) {
        CPU_NS.addAndGet(visibilityCpuNs);
        SECTIONS_CONSIDERED.addAndGet(sectionsConsidered);
        SECTIONS_VISIBLE.set(sectionsVisible);
        STATE_CHANGES.addAndGet(visibilityStateChanges);
        FULL_REBUILDS.addAndGet(fullRebuilds);
        INCREMENTAL_UPDATES.addAndGet(incrementalUpdates);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                CPU_NS.get(),
                SECTIONS_CONSIDERED.get(),
                SECTIONS_VISIBLE.get(),
                STATE_CHANGES.get(),
                FULL_REBUILDS.get(),
                INCREMENTAL_UPDATES.get());
    }

    public record Snapshot(
            long visibilityCpuNs,
            int sectionsConsidered,
            int sectionsVisible,
            int visibilityStateChanges,
            int fullRebuilds,
            int incrementalUpdates) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"visibilityMetrics\": {\n")
                    .append("    \"visibilityCpuNs\": ").append(this.visibilityCpuNs).append(",\n")
                    .append("    \"sectionsConsidered\": ").append(this.sectionsConsidered).append(",\n")
                    .append("    \"sectionsVisible\": ").append(this.sectionsVisible).append(",\n")
                    .append("    \"visibilityStateChanges\": ").append(this.visibilityStateChanges).append(",\n")
                    .append("    \"fullRebuilds\": ").append(this.fullRebuilds).append(",\n")
                    .append("    \"incrementalUpdates\": ").append(this.incrementalUpdates).append("\n")
                    .append("  }");
        }
    }
}
