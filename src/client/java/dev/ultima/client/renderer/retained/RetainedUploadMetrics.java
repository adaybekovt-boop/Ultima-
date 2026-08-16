package dev.ultima.client.renderer.retained;

/**
 * Per-frame GPU update counters for the retained foundation.
 * Reset at the start of each prepare.
 *
 * <p>Map/unmap/fence fields count operations Ultima itself issued. They do not
 * observe driver or Vulkan barrier stalls from {@code writeToBuffer}.
 */
public final class RetainedUploadMetrics {
    public int mapCalls;
    public int unmapCalls;
    public int writeToBufferCalls;
    public long writeToBufferBytes;
    public long metadataBytesWritten;
    public long commandBytesWritten;
    public int metadataDirtyRanges;
    public int commandDirtyRanges;
    public int commandRecordsChanged;
    public int immutableCommandWrites;
    public int visibilityCommandWrites;
    public int bufferReallocs;
    public int commandBufferReallocs;
    public long fenceWaitNs;
    public long mapWaitNs;
    public int renderPasses;
    public int encoders;
    public int headerWrites;
    public int sectionTableSlotsWritten;
    public long gpuTerrainNs;
    public boolean gpuTimingSupported;

    public void reset() {
        this.mapCalls = 0;
        this.unmapCalls = 0;
        this.writeToBufferCalls = 0;
        this.writeToBufferBytes = 0L;
        this.metadataBytesWritten = 0L;
        this.commandBytesWritten = 0L;
        this.metadataDirtyRanges = 0;
        this.commandDirtyRanges = 0;
        this.commandRecordsChanged = 0;
        this.immutableCommandWrites = 0;
        this.visibilityCommandWrites = 0;
        this.bufferReallocs = 0;
        this.commandBufferReallocs = 0;
        this.fenceWaitNs = 0L;
        this.mapWaitNs = 0L;
        this.renderPasses = 0;
        this.encoders = 0;
        this.headerWrites = 0;
        this.sectionTableSlotsWritten = 0;
        this.gpuTerrainNs = 0L;
        this.gpuTimingSupported = false;
    }
}
