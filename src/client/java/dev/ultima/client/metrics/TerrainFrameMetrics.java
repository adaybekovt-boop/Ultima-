package dev.ultima.client.metrics;

import dev.ultima.client.renderer.retained.RetainedUploadMetrics;
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
    private static int mapCalls;
    private static int unmapCalls;
    private static int writeToBufferCalls;
    private static long metadataBytesWritten;
    private static long commandBytesWritten;
    private static int dirtyRanges;
    private static int commandRecordsChanged;
    private static int immutableCommandWrites;
    private static int visibilityCommandWrites;
    private static int bufferReallocs;
    private static long fenceWaitNs;
    private static long mapWaitNs;
    private static int renderPasses;
    private static int encoders;
    private static int headerWrites;
    private static int sectionTableSlotsWritten;
    private static long gpuTerrainNs;
    private static boolean gpuTimingSupported;

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
        mapCalls = 0;
        unmapCalls = 0;
        writeToBufferCalls = 0;
        metadataBytesWritten = 0L;
        commandBytesWritten = 0L;
        dirtyRanges = 0;
        commandRecordsChanged = 0;
        immutableCommandWrites = 0;
        visibilityCommandWrites = 0;
        bufferReallocs = 0;
        fenceWaitNs = 0L;
        mapWaitNs = 0L;
        renderPasses = 0;
        encoders = 0;
        headerWrites = 0;
        sectionTableSlotsWritten = 0;
        gpuTerrainNs = 0L;
        gpuTimingSupported = false;
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

    public static void recordRetainedUploads(final RetainedUploadMetrics metrics) {
        mapCalls = metrics.mapCalls;
        unmapCalls = metrics.unmapCalls;
        writeToBufferCalls = metrics.writeToBufferCalls;
        metadataBytesWritten = metrics.metadataBytesWritten;
        commandBytesWritten = metrics.commandBytesWritten;
        dirtyRanges = metrics.dirtyRanges;
        commandRecordsChanged = metrics.commandRecordsChanged;
        immutableCommandWrites = metrics.immutableCommandWrites;
        visibilityCommandWrites = metrics.visibilityCommandWrites;
        bufferReallocs = metrics.bufferReallocs;
        fenceWaitNs = metrics.fenceWaitNs;
        mapWaitNs = metrics.mapWaitNs;
        renderPasses = metrics.renderPasses;
        encoders = metrics.encoders;
        headerWrites = metrics.headerWrites;
        sectionTableSlotsWritten = metrics.sectionTableSlotsWritten;
        gpuTerrainNs = metrics.gpuTerrainNs;
        gpuTimingSupported = metrics.gpuTimingSupported;
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
                submitMode,
                mapCalls,
                unmapCalls,
                writeToBufferCalls,
                metadataBytesWritten,
                commandBytesWritten,
                dirtyRanges,
                commandRecordsChanged,
                immutableCommandWrites,
                visibilityCommandWrites,
                bufferReallocs,
                fenceWaitNs,
                mapWaitNs,
                renderPasses,
                encoders,
                headerWrites,
                sectionTableSlotsWritten,
                gpuTerrainNs,
                gpuTimingSupported);
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
            String submitMode,
            int mapCalls,
            int unmapCalls,
            int writeToBufferCalls,
            long metadataBytesWritten,
            long commandBytesWritten,
            int dirtyRanges,
            int commandRecordsChanged,
            int immutableCommandWrites,
            int visibilityCommandWrites,
            int bufferReallocs,
            long fenceWaitNs,
            long mapWaitNs,
            int renderPasses,
            int encoders,
            int headerWrites,
            int sectionTableSlotsWritten,
            long gpuTerrainNs,
            boolean gpuTimingSupported) {
        public void appendJson(final StringBuilder json) {
            json.append("  \"terrainMetrics\": {\n")
                    .append("    \"prepareNs\": ").append(this.prepareNsAccum).append(",\n")
                    .append("    \"commandNs\": ").append(this.commandNsAccum).append(",\n")
                    .append("    \"submitNs\": ").append(this.submitNsAccum).append(",\n")
                    .append("    \"wholeFrameNs\": ").append(this.wholeFrameNs).append(",\n")
                    .append("    \"gpuFrameNs\": ").append(this.wholeFrameNs > 0L ? this.gpuFrameNs : this.gpuTerrainNs).append(",\n")
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
                    .append("    \"submitMode\": \"").append(this.submitMode).append("\",\n")
                    .append("    \"mapCalls\": ").append(this.mapCalls).append(",\n")
                    .append("    \"unmapCalls\": ").append(this.unmapCalls).append(",\n")
                    .append("    \"writeToBufferCalls\": ").append(this.writeToBufferCalls).append(",\n")
                    .append("    \"metadataBytesWritten\": ").append(this.metadataBytesWritten).append(",\n")
                    .append("    \"commandBytesWritten\": ").append(this.commandBytesWritten).append(",\n")
                    .append("    \"dirtyRanges\": ").append(this.dirtyRanges).append(",\n")
                    .append("    \"commandRecordsChanged\": ").append(this.commandRecordsChanged).append(",\n")
                    .append("    \"immutableCommandWrites\": ").append(this.immutableCommandWrites).append(",\n")
                    .append("    \"visibilityCommandWrites\": ").append(this.visibilityCommandWrites).append(",\n")
                    .append("    \"bufferReallocs\": ").append(this.bufferReallocs).append(",\n")
                    .append("    \"fenceWaitNs\": ").append(this.fenceWaitNs).append(",\n")
                    .append("    \"mapWaitNs\": ").append(this.mapWaitNs).append(",\n")
                    .append("    \"renderPasses\": ").append(this.renderPasses).append(",\n")
                    .append("    \"encoders\": ").append(this.encoders).append(",\n")
                    .append("    \"headerWrites\": ").append(this.headerWrites).append(",\n")
                    .append("    \"sectionTableSlotsWritten\": ").append(this.sectionTableSlotsWritten).append(",\n")
                    .append("    \"gpuTerrainNs\": ").append(this.gpuTerrainNs).append(",\n")
                    .append("    \"gpuTimingSupported\": ").append(this.gpuTimingSupported).append("\n")
                    .append("  }");
        }
    }
}
