package dev.ultima.meshing;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Code-only mesher counters for later hardware A/B. Not a performance claim.
 */
public final class MesherMetrics {
    private static final AtomicLong SNAPSHOT_BUILD_NS = new AtomicLong();
    private static final AtomicLong MESH_BUILD_NS = new AtomicLong();
    private static final AtomicLong BLOCKS_VISITED = new AtomicLong();
    private static final AtomicLong MODEL_CALLS = new AtomicLong();
    private static final AtomicLong FLUID_CALLS = new AtomicLong();
    private static final AtomicLong VERTICES_EMITTED = new AtomicLong();
    private static final AtomicLong BYTES_EMITTED = new AtomicLong();
    private static final AtomicLong TEMPORARY_ALLOCATION_PROXY = new AtomicLong();
    private static final AtomicLong REBUILD_COUNT = new AtomicLong();
    private static final AtomicLong WORKER_QUEUE_LATENCY_NS = new AtomicLong();

    private MesherMetrics() {
    }

    public static void reset() {
        SNAPSHOT_BUILD_NS.set(0L);
        MESH_BUILD_NS.set(0L);
        BLOCKS_VISITED.set(0L);
        MODEL_CALLS.set(0L);
        FLUID_CALLS.set(0L);
        VERTICES_EMITTED.set(0L);
        BYTES_EMITTED.set(0L);
        TEMPORARY_ALLOCATION_PROXY.set(0L);
        REBUILD_COUNT.set(0L);
        WORKER_QUEUE_LATENCY_NS.set(0L);
    }

    public static void recordCompile(
            final long snapshotBuildNs,
            final long meshBuildNs,
            final long blocksVisited,
            final long modelCalls,
            final long fluidCalls,
            final long verticesEmitted,
            final long bytesEmitted,
            final long temporaryAllocationProxy,
            final long workerQueueLatencyNs) {
        SNAPSHOT_BUILD_NS.addAndGet(snapshotBuildNs);
        MESH_BUILD_NS.addAndGet(meshBuildNs);
        BLOCKS_VISITED.addAndGet(blocksVisited);
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
        return new Snapshot(
                SNAPSHOT_BUILD_NS.get(),
                MESH_BUILD_NS.get(),
                BLOCKS_VISITED.get(),
                MODEL_CALLS.get(),
                FLUID_CALLS.get(),
                VERTICES_EMITTED.get(),
                BYTES_EMITTED.get(),
                TEMPORARY_ALLOCATION_PROXY.get(),
                REBUILD_COUNT.get(),
                WORKER_QUEUE_LATENCY_NS.get());
    }

    public record Snapshot(
            long snapshotBuildNs,
            long meshBuildNs,
            long blocksVisited,
            long modelCalls,
            long fluidCalls,
            long verticesEmitted,
            long bytesEmitted,
            long temporaryAllocationProxy,
            long rebuildCount,
            long workerQueueLatencyNs) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"mesherMetrics\": {\n")
                    .append("    \"snapshotBuildNs\": ").append(this.snapshotBuildNs).append(",\n")
                    .append("    \"meshBuildNs\": ").append(this.meshBuildNs).append(",\n")
                    .append("    \"blocksVisited\": ").append(this.blocksVisited).append(",\n")
                    .append("    \"modelCalls\": ").append(this.modelCalls).append(",\n")
                    .append("    \"fluidCalls\": ").append(this.fluidCalls).append(",\n")
                    .append("    \"verticesEmitted\": ").append(this.verticesEmitted).append(",\n")
                    .append("    \"bytesEmitted\": ").append(this.bytesEmitted).append(",\n")
                    .append("    \"temporaryAllocationProxy\": ").append(this.temporaryAllocationProxy).append(",\n")
                    .append("    \"rebuildCount\": ").append(this.rebuildCount).append(",\n")
                    .append("    \"workerQueueLatencyNs\": ").append(this.workerQueueLatencyNs).append("\n")
                    .append("  }");
        }
    }
}
