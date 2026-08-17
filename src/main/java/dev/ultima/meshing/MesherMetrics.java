package dev.ultima.meshing;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Code-only mesher counters. Not a frame-time or GPU performance claim.
 */
public final class MesherMetrics {
    private static final AtomicLong SNAPSHOT_BUILD_NS = new AtomicLong();
    private static final AtomicLong MESH_BUILD_NS = new AtomicLong();
    private static final AtomicLong BLOCKS_VISITED = new AtomicLong();
    private static final AtomicLong FAST_PATH_BLOCKS = new AtomicLong();
    private static final AtomicLong FALLBACK_BLOCKS = new AtomicLong();
    private static final AtomicLong MODEL_CALLS = new AtomicLong();
    private static final AtomicLong FLUID_CALLS = new AtomicLong();
    private static final AtomicLong VERTICES_EMITTED = new AtomicLong();
    private static final AtomicLong BYTES_EMITTED = new AtomicLong();
    private static final AtomicLong TEMPORARY_ALLOCATION_PROXY = new AtomicLong();
    private static final AtomicLong REBUILD_COUNT = new AtomicLong();
    private static final AtomicLong WORKER_QUEUE_LATENCY_NS = new AtomicLong();
    private static final AtomicLong SECTION_FAILURES = new AtomicLong();
    private static final AtomicLong CIRCUIT_BREAKER_TRIPS = new AtomicLong();
    private static final AtomicLongArray FALLBACK_BY_REASON =
            new AtomicLongArray(FastPathCriteria.Reason.values().length);

    private MesherMetrics() {
    }

    public static void reset() {
        SNAPSHOT_BUILD_NS.set(0L);
        MESH_BUILD_NS.set(0L);
        BLOCKS_VISITED.set(0L);
        FAST_PATH_BLOCKS.set(0L);
        FALLBACK_BLOCKS.set(0L);
        MODEL_CALLS.set(0L);
        FLUID_CALLS.set(0L);
        VERTICES_EMITTED.set(0L);
        BYTES_EMITTED.set(0L);
        TEMPORARY_ALLOCATION_PROXY.set(0L);
        REBUILD_COUNT.set(0L);
        WORKER_QUEUE_LATENCY_NS.set(0L);
        SECTION_FAILURES.set(0L);
        CIRCUIT_BREAKER_TRIPS.set(0L);
        for (int i = 0; i < FALLBACK_BY_REASON.length(); i++) {
            FALLBACK_BY_REASON.set(i, 0L);
        }
        MesherCircuitBreaker.get().reset();
    }

    public static void recordCompile(
            final long snapshotBuildNs,
            final long meshBuildNs,
            final long blocksVisited,
            final long fastPathBlocks,
            final long fallbackBlocks,
            final long modelCalls,
            final long fluidCalls,
            final long verticesEmitted,
            final long bytesEmitted,
            final long temporaryAllocationProxy,
            final long workerQueueLatencyNs) {
        SNAPSHOT_BUILD_NS.addAndGet(snapshotBuildNs);
        MESH_BUILD_NS.addAndGet(meshBuildNs);
        BLOCKS_VISITED.addAndGet(blocksVisited);
        FAST_PATH_BLOCKS.addAndGet(fastPathBlocks);
        FALLBACK_BLOCKS.addAndGet(fallbackBlocks);
        MODEL_CALLS.addAndGet(modelCalls);
        FLUID_CALLS.addAndGet(fluidCalls);
        VERTICES_EMITTED.addAndGet(verticesEmitted);
        BYTES_EMITTED.addAndGet(bytesEmitted);
        if (temporaryAllocationProxy >= 0L) {
            TEMPORARY_ALLOCATION_PROXY.addAndGet(temporaryAllocationProxy);
        }
        REBUILD_COUNT.incrementAndGet();
        WORKER_QUEUE_LATENCY_NS.addAndGet(workerQueueLatencyNs);
    }

    public static void recordDecision(final FastPathCriteria.Result result) {
        if (result.fastPath()) {
            return;
        }
        FALLBACK_BY_REASON.incrementAndGet(result.reason().ordinal());
    }

    public static void recordSectionFailure() {
        SECTION_FAILURES.incrementAndGet();
        FALLBACK_BY_REASON.incrementAndGet(FastPathCriteria.Reason.SECTION_FAIL_OPEN.ordinal());
    }

    public static void recordCircuitBreakerTrip() {
        CIRCUIT_BREAKER_TRIPS.incrementAndGet();
    }

    public static long threadAllocatedBytes() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean allocated && allocated.isThreadAllocatedMemorySupported()) {
                return allocated.getThreadAllocatedBytes(Thread.currentThread().threadId());
            }
        } catch (Throwable ignored) {
            return -1L;
        }
        return -1L;
    }

    public static Snapshot snapshot() {
        long[] byReason = new long[FALLBACK_BY_REASON.length()];
        for (int i = 0; i < byReason.length; i++) {
            byReason[i] = FALLBACK_BY_REASON.get(i);
        }
        long visited = BLOCKS_VISITED.get();
        long fast = FAST_PATH_BLOCKS.get();
        return new Snapshot(
                SNAPSHOT_BUILD_NS.get(),
                MESH_BUILD_NS.get(),
                visited,
                fast,
                FALLBACK_BLOCKS.get(),
                MODEL_CALLS.get(),
                FLUID_CALLS.get(),
                VERTICES_EMITTED.get(),
                BYTES_EMITTED.get(),
                TEMPORARY_ALLOCATION_PROXY.get(),
                REBUILD_COUNT.get(),
                WORKER_QUEUE_LATENCY_NS.get(),
                SECTION_FAILURES.get(),
                CIRCUIT_BREAKER_TRIPS.get(),
                visited == 0L ? 0.0 : (double)fast / (double)visited,
                byReason);
    }

    public record Snapshot(
            long snapshotBuildNs,
            long meshBuildNs,
            long blocksVisited,
            long fastPathBlocks,
            long fallbackBlocks,
            long modelCalls,
            long fluidCalls,
            long verticesEmitted,
            long bytesEmitted,
            long temporaryAllocationProxy,
            long rebuildCount,
            long workerQueueLatencyNs,
            long meshFastPathFailures,
            long meshFastPathCircuitBreakerTrips,
            double fastPathCoverageOfNonAir,
            long[] fallbackByReason) {
        public long sectionsPerSecond() {
            if (this.meshBuildNs <= 0L || this.rebuildCount <= 0L) {
                return 0L;
            }
            return this.rebuildCount * 1_000_000_000L / this.meshBuildNs;
        }

        public long meshBuildNsPerSection() {
            return this.rebuildCount == 0L ? 0L : this.meshBuildNs / this.rebuildCount;
        }

        public long fallbackCount(final FastPathCriteria.Reason reason) {
            return this.fallbackByReason[reason.ordinal()];
        }

        public void appendJson(final StringBuilder json) {
            String scene = System.getProperty("ultima.clientBenchmark.scene", "unspecified")
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            json.append("  \"mesherMetrics\": {\n")
                    .append("    \"scene\": \"").append(scene).append("\",\n")
                    .append("    \"snapshotBuildNs\": ").append(this.snapshotBuildNs).append(",\n")
                    .append("    \"meshBuildNs\": ").append(this.meshBuildNs).append(",\n")
                    .append("    \"meshBuildNsPerSection\": ").append(this.meshBuildNsPerSection()).append(",\n")
                    .append("    \"sectionsPerSecond\": ").append(this.sectionsPerSecond()).append(",\n")
                    .append("    \"blocksVisited\": ").append(this.blocksVisited).append(",\n")
                    .append("    \"fastPathBlocks\": ").append(this.fastPathBlocks).append(",\n")
                    .append("    \"fallbackBlocks\": ").append(this.fallbackBlocks).append(",\n")
                    .append("    \"fastPathCoverageOfNonAir\": ").append(this.fastPathCoverageOfNonAir).append(",\n")
                    .append("    \"modelCalls\": ").append(this.modelCalls).append(",\n")
                    .append("    \"fluidCalls\": ").append(this.fluidCalls).append(",\n")
                    .append("    \"verticesEmitted\": ").append(this.verticesEmitted).append(",\n")
                    .append("    \"bytesEmitted\": ").append(this.bytesEmitted).append(",\n")
                    .append("    \"temporaryAllocationProxy\": ").append(this.temporaryAllocationProxy).append(",\n")
                    .append("    \"rebuildCount\": ").append(this.rebuildCount).append(",\n")
                    .append("    \"workerQueueLatencyNs\": ").append(this.workerQueueLatencyNs).append(",\n")
                    .append("    \"meshFastPathFailures\": ").append(this.meshFastPathFailures).append(",\n")
                    .append("    \"meshFastPathCircuitBreakerTrips\": ").append(this.meshFastPathCircuitBreakerTrips).append(",\n")
                    .append("    \"fallbackByReason\": {\n");
            FastPathCriteria.Reason[] reasons = FastPathCriteria.Reason.values();
            for (int i = 0; i < reasons.length; i++) {
                json.append("      \"").append(reasons[i].name()).append("\": ").append(this.fallbackByReason[i]);
                if (i + 1 < reasons.length) {
                    json.append(',');
                }
                json.append('\n');
            }
            json.append("    },\n")
                    .append("    \"disclaimer\": \"CPU meshing counters only. NOT a real FPS/GPU claim.\"\n")
                    .append("  }");
        }
    }
}
