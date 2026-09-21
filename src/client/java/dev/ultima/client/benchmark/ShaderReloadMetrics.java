package dev.ultima.client.benchmark;

/** Benchmark-only wall timing around Minecraft's complete ShaderManager.apply boundary. */
public final class ShaderReloadMetrics {
    private static long reloads;
    private static long totalNanos;
    private static long maximumNanos;
    private static long lastNanos;
    private static long requested;
    private static String unavailableReason = "";

    private ShaderReloadMetrics() {
    }

    public static void record(final long durationNanos) {
        long duration = Math.max(0L, durationNanos);
        reloads++;
        totalNanos += duration;
        maximumNanos = Math.max(maximumNanos, duration);
        lastNanos = duration;
    }

    public static void markRequested() {
        requested++;
    }

    public static void markUnavailable(final String reason) {
        unavailableReason = reason == null ? "unknown" : reason;
    }

    public static Snapshot snapshot() {
        return new Snapshot(reloads, totalNanos, maximumNanos, lastNanos, requested, unavailableReason);
    }

    public record Snapshot(
            long reloads,
            long totalNanos,
            long maximumNanos,
            long lastNanos,
            long requested,
            String unavailableReason) {
    }
}
