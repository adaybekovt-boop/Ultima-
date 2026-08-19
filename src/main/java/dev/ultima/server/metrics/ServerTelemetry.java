package dev.ultima.server.metrics;

/**
 * Public write API for server optimizations (block-entity sleeping, AI dirty tracking,
 * chunk serialize caches). Always-on counters increment when the module is enabled; semantic
 * events are recorded only during {@code /ultima profile}.
 *
 * <p>Calling these methods does not change gameplay. Hopper sleeping already populates
 * {@code be.sleeping}, {@code be.wakeups}, and {@code be.thrashes} when that module is on.
 */
public final class ServerTelemetry {
    private ServerTelemetry() {
    }

    public static boolean isEnabled() {
        return ServerMetrics.isEnabled();
    }

    public static boolean isDetailed() {
        return ServerMetrics.isDetailed();
    }

    public static void recordBeSleepingCount(final int sleeping) {
        ServerMetrics.setGauge(MetricId.BE_SLEEPING, sleeping);
    }

    public static void recordBeWakeup() {
        ServerMetrics.addCount(MetricId.BE_WAKEUPS, 1);
    }

    public static void recordBeThrash() {
        ServerMetrics.addCount(MetricId.BE_THRASHES, 1);
    }

    public static void recordHopperWakeAdjacentInventory() {
        ServerMetrics.addCount(MetricId.BE_WAKEUPS, 1);
        ServerMetrics.event(SemanticEventKind.HOPPER_WAKE_ADJACENT_INVENTORY);
    }

    public static void recordAiCleanSkip() {
        ServerMetrics.addCount(MetricId.AI_CLEAN_SKIPS, 1);
    }

    public static void recordAiInvalidation() {
        ServerMetrics.addCount(MetricId.AI_INVALIDATIONS, 1);
    }

    public static void recordTrackerRecalcSectionBoundary() {
        ServerMetrics.event(SemanticEventKind.TRACKER_RECALC_SECTION_BOUNDARY);
    }

    public static void recordChunkSerializeCacheMiss() {
        ServerMetrics.event(SemanticEventKind.CHUNK_SERIALIZE_CACHE_MISS);
    }

    public static void event(final SemanticEventKind kind, final String message) {
        ServerMetrics.event(kind, message);
    }
}
