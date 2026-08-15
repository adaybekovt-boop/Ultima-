package dev.ultima.client.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent terrain-cost counters. Cheap enough to run whenever the client
 * benchmark recorder or retained terrain is active. Values are render-thread
 * except rebuild/upload which workers may increment.
 */
public final class TerrainFrameMetrics {
    private static final AtomicLong CHUNK_REBUILDS = new AtomicLong();
    private static final AtomicLong CHUNK_UPLOADS = new AtomicLong();

    private static long prepareStartNs;
    private static long submitStartNs;
    private static long commandStartNs;

    private static long prepareNs;
    private static long commandNs;
    private static long submitNs;
    private static long framePrepareNsAccum;
    private static long frameCommandNsAccum;
    private static long frameSubmitNsAccum;

    private static int terrainDraws;
    private static int visibleSections;
    private static int visibleSectionLayers;
    private static int uniformRecords;
    private static int commandRebuilds;
    private static int metadataUpdates;
    private static long allocationBytes;
    private static boolean retainedActive;
    private static boolean commandBatchesReused;
    private static String submitMode = "vanilla";

    private TerrainFrameMetrics() {
    }

    public static void beginFrame() {
        framePrepareNsAccum = 0L;
        frameCommandNsAccum = 0L;
        frameSubmitNsAccum = 0L;
        terrainDraws = 0;
        visibleSections = 0;
        visibleSectionLayers = 0;
        uniformRecords = 0;
        commandRebuilds = 0;
        metadataUpdates = 0;
        allocationBytes = 0L;
        retainedActive = false;
        commandBatchesReused = false;
        submitMode = "vanilla";
    }

    public static void beginPrepare() {
        prepareStartNs = System.nanoTime();
    }

    public static void endPrepare() {
        if (prepareStartNs != 0L) {
            prepareNs = System.nanoTime() - prepareStartNs;
            framePrepareNsAccum += prepareNs;
            prepareStartNs = 0L;
        }
    }

    public static void beginCommand() {
        commandStartNs = System.nanoTime();
    }

    public static void endCommand() {
        if (commandStartNs != 0L) {
            commandNs = System.nanoTime() - commandStartNs;
            frameCommandNsAccum += commandNs;
            commandStartNs = 0L;
        }
    }

    public static void beginSubmit() {
        submitStartNs = System.nanoTime();
    }

    public static void endSubmit() {
        if (submitStartNs != 0L) {
            submitNs = System.nanoTime() - submitStartNs;
            frameSubmitNsAccum += submitNs;
            submitStartNs = 0L;
        }
    }

    public static void recordVisible(final int sections, final int sectionLayers, final int draws, final int uniforms) {
        visibleSections = sections;
        visibleSectionLayers = sectionLayers;
        terrainDraws = draws;
        uniformRecords = uniforms;
    }

    public static void addDraws(final int draws) {
        terrainDraws += draws;
    }

    public static void addUniforms(final int records) {
        uniformRecords += records;
    }

    public static void addCommandRebuilds(final int count) {
        commandRebuilds += count;
    }

    public static void addMetadataUpdates(final int count) {
        metadataUpdates += count;
    }

    public static void addAllocationBytes(final long bytes) {
        allocationBytes += bytes;
    }

    public static void setRetainedActive(final boolean active, final String mode) {
        retainedActive = active;
        submitMode = mode;
    }

    public static boolean isRetainedActive() {
        return retainedActive;
    }

    public static void setCommandBatchesReused(final boolean reused) {
        commandBatchesReused = reused;
    }

    public static boolean isCommandBatchesReused() {
        return commandBatchesReused;
    }

    public static void incrementRebuilds() {
        CHUNK_REBUILDS.incrementAndGet();
    }

    public static void incrementUploads() {
        CHUNK_UPLOADS.incrementAndGet();
    }

    public static void resetLifetime() {
        CHUNK_REBUILDS.set(0L);
        CHUNK_UPLOADS.set(0L);
    }

    public static Snapshot snapshot(final long wholeFrameNs, final long gpuFrameNs) {
        return new Snapshot(
                prepareNs,
                commandNs,
                submitNs,
                framePrepareNsAccum,
                frameCommandNsAccum,
                frameSubmitNsAccum,
                wholeFrameNs,
                gpuFrameNs,
                terrainDraws,
                visibleSections,
                visibleSectionLayers,
                uniformRecords,
                commandRebuilds,
                metadataUpdates,
                allocationBytes,
                CHUNK_REBUILDS.get(),
                CHUNK_UPLOADS.get(),
                retainedActive,
                commandBatchesReused,
                submitMode);
    }

    public record Snapshot(
            long prepareNs,
            long commandNs,
            long submitNs,
            long prepareNsAccum,
            long commandNsAccum,
            long submitNsAccum,
            long wholeFrameNs,
            long gpuFrameNs,
            int terrainDraws,
            int visibleSections,
            int visibleSectionLayers,
            int uniformRecords,
            int commandRebuilds,
            int metadataUpdates,
            long allocationBytes,
            long chunkRebuilds,
            long chunkUploads,
            boolean retainedActive,
            boolean commandBatchesReused,
            String submitMode) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"terrainMetrics\": {\n")
                    .append("    \"prepareNs\": ").append(this.prepareNsAccum).append(",\n")
                    .append("    \"commandNs\": ").append(this.commandNsAccum).append(",\n")
                    .append("    \"submitNs\": ").append(this.submitNsAccum).append(",\n")
                    .append("    \"wholeFrameNs\": ").append(this.wholeFrameNs).append(",\n")
                    .append("    \"gpuFrameNs\": ").append(this.gpuFrameNs).append(",\n")
                    .append("    \"terrainDraws\": ").append(this.terrainDraws).append(",\n")
                    .append("    \"visibleSections\": ").append(this.visibleSections).append(",\n")
                    .append("    \"visibleSectionLayers\": ").append(this.visibleSectionLayers).append(",\n")
                    .append("    \"uniformRecords\": ").append(this.uniformRecords).append(",\n")
                    .append("    \"commandRebuilds\": ").append(this.commandRebuilds).append(",\n")
                    .append("    \"metadataUpdates\": ").append(this.metadataUpdates).append(",\n")
                    .append("    \"allocationBytes\": ").append(this.allocationBytes).append(",\n")
                    .append("    \"chunkRebuilds\": ").append(this.chunkRebuilds).append(",\n")
                    .append("    \"chunkUploads\": ").append(this.chunkUploads).append(",\n")
                    .append("    \"retainedActive\": ").append(this.retainedActive).append(",\n")
                    .append("    \"commandBatchesReused\": ").append(this.commandBatchesReused).append(",\n")
                    .append("    \"submitMode\": \"").append(this.submitMode).append("\"\n")
                    .append("  }");
        }
    }
}
