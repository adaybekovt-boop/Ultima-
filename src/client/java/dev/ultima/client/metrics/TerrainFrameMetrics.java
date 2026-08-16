package dev.ultima.client.metrics;

import dev.ultima.client.renderer.retained.RetainedUploadMetrics;
import dev.ultima.metrics.TerrainCpuPhases;
import dev.ultima.metrics.TerrainCpuPhases.Phase;
import dev.ultima.retained.CommandPopulation;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;

/**
 * Independent terrain-cost counters. Cheap enough to run whenever the client
 * benchmark recorder or retained terrain is active.
 *
 * <p>A/B-comparable CPU totals are {@code terrainPrepareCpuNs +
 * terrainOpaqueSubmitCpuNs + terrainTranslucentSubmitCpuNs}. Command generation
 * is diagnostic and is already inside prepare on the retained path.
 *
 * <p>Map/fence counters are Ultima-issued operations only. Zero does not prove
 * the driver or GPU never stalled.
 */
public final class TerrainFrameMetrics {
    private static final AtomicLong CHUNK_REBUILDS = new AtomicLong();
    private static final AtomicLong CHUNK_UPLOADS = new AtomicLong();
    private static final TerrainCpuPhases CPU = new TerrainCpuPhases();

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
    private static int ultimaIssuedMapCalls;
    private static int ultimaIssuedUnmapCalls;
    private static int writeToBufferCalls;
    private static long writeToBufferBytes;
    private static long metadataBytesWritten;
    private static long commandBytesWritten;
    private static int metadataDirtyRanges;
    private static int commandDirtyRanges;
    private static int commandRecordsChanged;
    private static int immutableCommandWrites;
    private static int visibilityCommandWrites;
    private static int bufferReallocs;
    private static long ultimaIssuedFenceWaitNs;
    private static long ultimaIssuedMapWaitNs;
    private static int renderPasses;
    private static int encoders;
    private static int headerWrites;
    private static int sectionTableSlotsWritten;
    private static long gpuTerrainNs;
    private static boolean gpuTimingSupported;
    private static boolean opaqueSubmitSucceeded;
    private static boolean failOpenThisFrame;

    private static int submitGroupCount;
    private static int totalCommandRecords;
    private static int liveCommandRecords;
    private static int hiddenZeroInstanceCommands;
    private static double liveToTotalRatio = 1.0;
    private static int largestSubmitGroupCommands;
    private static int commandArrayCapacity;
    private static long commandBufferCapacityBytes;
    private static int commandBufferReallocs;
    private static int commandArrayReallocs;
    private static int commandsAdded;
    private static int commandsRemoved;
    private static int visibilityToggles;
    private static int maxTotalCommandRecords;
    private static int maxHiddenCommands;
    private static double minLiveRatio = 1.0;
    private static boolean minLiveRatioSeen;
    private static int firstSampleTotalCommands = -1;
    private static int firstSampleLiveCommands = -1;
    private static int lastSampleTotalCommands;
    private static int lastSampleLiveCommands;

    private TerrainFrameMetrics() {
    }

    public static void beginFrame() {
        CPU.beginFrame();
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
        ultimaIssuedMapCalls = 0;
        ultimaIssuedUnmapCalls = 0;
        writeToBufferCalls = 0;
        writeToBufferBytes = 0L;
        metadataBytesWritten = 0L;
        commandBytesWritten = 0L;
        metadataDirtyRanges = 0;
        commandDirtyRanges = 0;
        commandRecordsChanged = 0;
        immutableCommandWrites = 0;
        visibilityCommandWrites = 0;
        bufferReallocs = 0;
        ultimaIssuedFenceWaitNs = 0L;
        ultimaIssuedMapWaitNs = 0L;
        renderPasses = 0;
        encoders = 0;
        headerWrites = 0;
        sectionTableSlotsWritten = 0;
        gpuTerrainNs = 0L;
        gpuTimingSupported = false;
        opaqueSubmitSucceeded = false;
        failOpenThisFrame = false;
        submitGroupCount = 0;
        totalCommandRecords = 0;
        liveCommandRecords = 0;
        hiddenZeroInstanceCommands = 0;
        liveToTotalRatio = 1.0;
        largestSubmitGroupCommands = 0;
        commandArrayCapacity = 0;
        commandBufferCapacityBytes = 0L;
        commandBufferReallocs = 0;
        commandArrayReallocs = 0;
        commandsAdded = 0;
        commandsRemoved = 0;
        visibilityToggles = 0;
    }

    public static void beginPrepare() {
        CPU.begin(Phase.PREPARE);
    }

    public static void endPrepare() {
        CPU.end(Phase.PREPARE);
    }

    public static void beginCommand() {
        CPU.begin(Phase.COMMAND);
    }

    public static void endCommand() {
        CPU.end(Phase.COMMAND);
    }

    public static void beginSubmit() {
        CPU.begin(Phase.OPAQUE_SUBMIT);
    }

    public static void endSubmit() {
        CPU.end(Phase.OPAQUE_SUBMIT);
    }

    public static void beginSubmit(final ChunkSectionLayerGroup group) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            CPU.begin(Phase.OPAQUE_SUBMIT);
        } else {
            CPU.begin(Phase.TRANSLUCENT_SUBMIT);
        }
    }

    public static void endSubmit(final ChunkSectionLayerGroup group) {
        if (group == ChunkSectionLayerGroup.OPAQUE) {
            CPU.end(Phase.OPAQUE_SUBMIT);
        } else {
            CPU.end(Phase.TRANSLUCENT_SUBMIT);
        }
    }

    public static void beginOpaqueSubmit() {
        CPU.begin(Phase.OPAQUE_SUBMIT);
    }

    public static void endOpaqueSubmit() {
        CPU.end(Phase.OPAQUE_SUBMIT);
    }

    /**
     * Close PREPARE after retained {@code prepareChunkRenders} cancels vanilla.
     * The terrain_metrics {@code @At("RETURN")} hook does not run on that new
     * return, so the mixin HEAD begin would otherwise leak depth=1.
     */
    public static void closeOpenPrepare() {
        CPU.closeOpen(Phase.PREPARE);
    }

    /**
     * Close OPAQUE_SUBMIT after retained {@code renderGroup(OPAQUE)} cancels
     * vanilla. Same RETURN-vs-cancel pairing hole as {@link #closeOpenPrepare()}.
     */
    public static void closeOpenOpaqueSubmit() {
        CPU.closeOpen(Phase.OPAQUE_SUBMIT);
    }

    public static void recordOpaqueSubmitOutcome(final boolean succeeded, final boolean failOpen) {
        opaqueSubmitSucceeded = succeeded;
        if (failOpen) {
            failOpenThisFrame = true;
        }
    }

    public static void recordFailOpen() {
        failOpenThisFrame = true;
        retainedActive = false;
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
        ultimaIssuedMapCalls = metrics.mapCalls;
        ultimaIssuedUnmapCalls = metrics.unmapCalls;
        writeToBufferCalls = metrics.writeToBufferCalls;
        writeToBufferBytes = metrics.writeToBufferBytes;
        metadataBytesWritten = metrics.metadataBytesWritten;
        commandBytesWritten = metrics.commandBytesWritten;
        metadataDirtyRanges = metrics.metadataDirtyRanges;
        commandDirtyRanges = metrics.commandDirtyRanges;
        commandRecordsChanged = metrics.commandRecordsChanged;
        immutableCommandWrites = metrics.immutableCommandWrites;
        visibilityCommandWrites = metrics.visibilityCommandWrites;
        bufferReallocs = metrics.bufferReallocs;
        ultimaIssuedFenceWaitNs = metrics.fenceWaitNs;
        ultimaIssuedMapWaitNs = metrics.mapWaitNs;
        renderPasses = metrics.renderPasses;
        encoders = metrics.encoders;
        headerWrites = metrics.headerWrites;
        sectionTableSlotsWritten = metrics.sectionTableSlotsWritten;
        gpuTerrainNs = metrics.gpuTerrainNs;
        gpuTimingSupported = metrics.gpuTimingSupported;
        commandBufferReallocs = metrics.commandBufferReallocs;
    }

    public static void recordCommandPopulation(
            final int groups,
            final int total,
            final int live,
            final int hidden,
            final int largestGroup,
            final int arrayCapacity,
            final long bufferBytes,
            final int arrayReallocsThisFrame,
            final int added,
            final int removed,
            final int toggles) {
        submitGroupCount = groups;
        totalCommandRecords = total;
        liveCommandRecords = live;
        hiddenZeroInstanceCommands = hidden;
        liveToTotalRatio = CommandPopulation.ratio(live, total);
        largestSubmitGroupCommands = largestGroup;
        commandArrayCapacity = arrayCapacity;
        commandBufferCapacityBytes = bufferBytes;
        commandArrayReallocs = arrayReallocsThisFrame;
        commandsAdded = added;
        commandsRemoved = removed;
        visibilityToggles = toggles;
        if (total > maxTotalCommandRecords) {
            maxTotalCommandRecords = total;
        }
        if (hidden > maxHiddenCommands) {
            maxHiddenCommands = hidden;
        }
        if (total > 0) {
            double ratio = CommandPopulation.ratio(live, total);
            if (!minLiveRatioSeen || ratio < minLiveRatio) {
                minLiveRatio = ratio;
                minLiveRatioSeen = true;
            }
        }
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
        maxTotalCommandRecords = 0;
        maxHiddenCommands = 0;
        minLiveRatio = 1.0;
        minLiveRatioSeen = false;
        firstSampleTotalCommands = -1;
        firstSampleLiveCommands = -1;
        lastSampleTotalCommands = 0;
        lastSampleLiveCommands = 0;
    }

    public static void markSampledFrame() {
        if (firstSampleTotalCommands < 0) {
            firstSampleTotalCommands = totalCommandRecords;
            firstSampleLiveCommands = liveCommandRecords;
        }
        lastSampleTotalCommands = totalCommandRecords;
        lastSampleLiveCommands = liveCommandRecords;
    }

    public static Snapshot snapshot(final long wholeFrameNs, final long gpuFrameNs) {
        long prepare = CPU.accumNs(Phase.PREPARE);
        long command = CPU.accumNs(Phase.COMMAND);
        long opaque = CPU.accumNs(Phase.OPAQUE_SUBMIT);
        long translucent = CPU.accumNs(Phase.TRANSLUCENT_SUBMIT);
        long total = CPU.totalCpuNs();
        return new Snapshot(
                prepare,
                command,
                opaque + translucent,
                prepare,
                command,
                opaque + translucent,
                opaque,
                translucent,
                total,
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
                ultimaIssuedMapCalls,
                ultimaIssuedUnmapCalls,
                writeToBufferCalls,
                writeToBufferBytes,
                metadataBytesWritten,
                commandBytesWritten,
                metadataDirtyRanges,
                commandDirtyRanges,
                commandRecordsChanged,
                immutableCommandWrites,
                visibilityCommandWrites,
                bufferReallocs,
                ultimaIssuedFenceWaitNs,
                ultimaIssuedMapWaitNs,
                renderPasses,
                encoders,
                headerWrites,
                sectionTableSlotsWritten,
                gpuTerrainNs,
                gpuTimingSupported,
                CPU.pairingBalanced(),
                opaqueSubmitSucceeded,
                failOpenThisFrame,
                submitGroupCount,
                totalCommandRecords,
                liveCommandRecords,
                hiddenZeroInstanceCommands,
                liveToTotalRatio,
                largestSubmitGroupCommands,
                commandArrayCapacity,
                commandBufferCapacityBytes,
                commandBufferReallocs,
                commandArrayReallocs,
                commandsAdded,
                commandsRemoved,
                visibilityToggles,
                maxTotalCommandRecords,
                maxHiddenCommands,
                minLiveRatioSeen ? minLiveRatio : 1.0,
                firstSampleTotalCommands,
                firstSampleLiveCommands,
                lastSampleTotalCommands,
                lastSampleLiveCommands);
    }

    public record Snapshot(
            long prepareNs,
            long commandNs,
            long submitNs,
            long prepareNsAccum,
            long commandNsAccum,
            long submitNsAccum,
            long opaqueSubmitNsAccum,
            long translucentSubmitNsAccum,
            long totalCpuNsAccum,
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
            int ultimaIssuedMapCalls,
            int ultimaIssuedUnmapCalls,
            int writeToBufferCalls,
            long writeToBufferBytes,
            long metadataBytesWritten,
            long commandBytesWritten,
            int metadataDirtyRanges,
            int commandDirtyRanges,
            int commandRecordsChanged,
            int immutableCommandWrites,
            int visibilityCommandWrites,
            int bufferReallocs,
            long ultimaIssuedFenceWaitNs,
            long ultimaIssuedMapWaitNs,
            int renderPasses,
            int encoders,
            int headerWrites,
            int sectionTableSlotsWritten,
            long gpuTerrainNs,
            boolean gpuTimingSupported,
            boolean timingPairingBalanced,
            boolean opaqueSubmitSucceeded,
            boolean failOpenThisFrame,
            int submitGroupCount,
            int totalCommandRecords,
            int liveCommandRecords,
            int hiddenZeroInstanceCommands,
            double liveToTotalRatio,
            int largestSubmitGroupCommands,
            int commandArrayCapacity,
            long commandBufferCapacityBytes,
            int commandBufferReallocs,
            int commandArrayReallocs,
            int commandsAdded,
            int commandsRemoved,
            int visibilityToggles,
            int maxTotalCommandRecords,
            int maxHiddenCommands,
            double minLiveRatio,
            int firstSampleTotalCommands,
            int firstSampleLiveCommands,
            int lastSampleTotalCommands,
            int lastSampleLiveCommands) {
        public int mapCalls() {
            return this.ultimaIssuedMapCalls;
        }

        public int unmapCalls() {
            return this.ultimaIssuedUnmapCalls;
        }

        public long fenceWaitNs() {
            return this.ultimaIssuedFenceWaitNs;
        }

        public long mapWaitNs() {
            return this.ultimaIssuedMapWaitNs;
        }

        public void appendJson(final StringBuilder json) {
            json.append("  \"terrainMetrics\": {\n")
                    .append("    \"timingModel\": \"ultima_stage1_symmetric_v1\",\n")
                    .append("    \"terrainPrepareCpuNs\": ").append(this.prepareNsAccum).append(",\n")
                    .append("    \"terrainOpaqueSubmitCpuNs\": ").append(this.opaqueSubmitNsAccum).append(",\n")
                    .append("    \"terrainTranslucentSubmitCpuNs\": ").append(this.translucentSubmitNsAccum).append(",\n")
                    .append("    \"terrainCommandCpuNs\": ").append(this.commandNsAccum).append(",\n")
                    .append("    \"terrainTotalCpuNs\": ").append(this.totalCpuNsAccum).append(",\n")
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
                    .append("    \"timingPairingBalanced\": ").append(this.timingPairingBalanced).append(",\n")
                    .append("    \"opaqueSubmitSucceeded\": ").append(this.opaqueSubmitSucceeded).append(",\n")
                    .append("    \"failOpenThisFrame\": ").append(this.failOpenThisFrame).append(",\n")
                    .append("    \"ultimaIssuedMapCalls\": ").append(this.ultimaIssuedMapCalls).append(",\n")
                    .append("    \"ultimaIssuedUnmapCalls\": ").append(this.ultimaIssuedUnmapCalls).append(",\n")
                    .append("    \"ultimaIssuedFenceWaitNs\": ").append(this.ultimaIssuedFenceWaitNs).append(",\n")
                    .append("    \"ultimaIssuedMapWaitNs\": ").append(this.ultimaIssuedMapWaitNs).append(",\n")
                    .append("    \"syncCountersScope\": \"ultima_issued_only\",\n")
                    .append("    \"driverImplicitSyncObserved\": false,\n")
                    .append("    \"writeToBufferMayInsertBackendBarriers\": true,\n")
                    .append("    \"writeToBufferCalls\": ").append(this.writeToBufferCalls).append(",\n")
                    .append("    \"writeToBufferBytes\": ").append(this.writeToBufferBytes).append(",\n")
                    .append("    \"metadataBytesWritten\": ").append(this.metadataBytesWritten).append(",\n")
                    .append("    \"commandBytesWritten\": ").append(this.commandBytesWritten).append(",\n")
                    .append("    \"metadataDirtyRanges\": ").append(this.metadataDirtyRanges).append(",\n")
                    .append("    \"commandDirtyRanges\": ").append(this.commandDirtyRanges).append(",\n")
                    .append("    \"commandRecordsChanged\": ").append(this.commandRecordsChanged).append(",\n")
                    .append("    \"immutableCommandWrites\": ").append(this.immutableCommandWrites).append(",\n")
                    .append("    \"visibilityCommandWrites\": ").append(this.visibilityCommandWrites).append(",\n")
                    .append("    \"bufferReallocs\": ").append(this.bufferReallocs).append(",\n")
                    .append("    \"renderPasses\": ").append(this.renderPasses).append(",\n")
                    .append("    \"encoders\": ").append(this.encoders).append(",\n")
                    .append("    \"headerWrites\": ").append(this.headerWrites).append(",\n")
                    .append("    \"sectionTableSlotsWritten\": ").append(this.sectionTableSlotsWritten).append(",\n")
                    .append("    \"gpuTerrainNs\": ").append(this.gpuTerrainNs).append(",\n")
                    .append("    \"gpuTimingSupported\": ").append(this.gpuTimingSupported).append(",\n")
                    .append("    \"submitGroupCount\": ").append(this.submitGroupCount).append(",\n")
                    .append("    \"totalCommandRecords\": ").append(this.totalCommandRecords).append(",\n")
                    .append("    \"liveCommandRecords\": ").append(this.liveCommandRecords).append(",\n")
                    .append("    \"hiddenZeroInstanceCommands\": ").append(this.hiddenZeroInstanceCommands).append(",\n")
                    .append("    \"liveToTotalRatio\": ").append(this.liveToTotalRatio).append(",\n")
                    .append("    \"largestSubmitGroupCommands\": ").append(this.largestSubmitGroupCommands).append(",\n")
                    .append("    \"commandArrayCapacity\": ").append(this.commandArrayCapacity).append(",\n")
                    .append("    \"commandBufferCapacityBytes\": ").append(this.commandBufferCapacityBytes).append(",\n")
                    .append("    \"commandBufferReallocs\": ").append(this.commandBufferReallocs).append(",\n")
                    .append("    \"commandArrayReallocs\": ").append(this.commandArrayReallocs).append(",\n")
                    .append("    \"commandsAdded\": ").append(this.commandsAdded).append(",\n")
                    .append("    \"commandsRemoved\": ").append(this.commandsRemoved).append(",\n")
                    .append("    \"visibilityToggles\": ").append(this.visibilityToggles).append(",\n")
                    .append("    \"maxTotalCommandRecords\": ").append(this.maxTotalCommandRecords).append(",\n")
                    .append("    \"maxHiddenCommands\": ").append(this.maxHiddenCommands).append(",\n")
                    .append("    \"minLiveRatio\": ").append(this.minLiveRatio).append(",\n")
                    .append("    \"firstSampleTotalCommands\": ").append(this.firstSampleTotalCommands).append(",\n")
                    .append("    \"firstSampleLiveCommands\": ").append(this.firstSampleLiveCommands).append(",\n")
                    .append("    \"lastSampleTotalCommands\": ").append(this.lastSampleTotalCommands).append(",\n")
                    .append("    \"lastSampleLiveCommands\": ").append(this.lastSampleLiveCommands).append("\n")
                    .append("  }");
        }
    }
}
