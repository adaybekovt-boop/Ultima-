package dev.ultima.client.benchmark;

/**
 * Lightweight counters enabled only during explicit benchmark runs.
 */
public final class ClientOptimizationCounters {
    private static final boolean ENABLED = Boolean.getBoolean("ultima.clientBenchmark");

    private static long chunkMatrixCopiesAvoided;
    private static long chunkLayerArraysAvoided;

    private ClientOptimizationCounters() {
    }

    public static void avoidedChunkMatrixCopy() {
        if (ENABLED) {
            chunkMatrixCopiesAvoided++;
        }
    }

    public static void avoidedChunkLayerArray() {
        if (ENABLED) {
            chunkLayerArraysAvoided++;
        }
    }

    public static void reset() {
        chunkMatrixCopiesAvoided = 0L;
        chunkLayerArraysAvoided = 0L;
    }

    public static long chunkMatrixCopiesAvoided() {
        return chunkMatrixCopiesAvoided;
    }

    public static long chunkLayerArraysAvoided() {
        return chunkLayerArraysAvoided;
    }
}
