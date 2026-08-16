package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.retained.SubmitCommandList;
import dev.ultima.util.BitSetRuns;
import dev.ultima.util.IndirectCommandPacking;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/**
 * One persistent submit group: same layer, vertex buffer, and index buffer.
 * Indirect commands live on the GPU and are patched in place.
 *
 * <p>CPU swap-remove lives in {@link SubmitCommandList}. This class owns the
 * GPU command buffer. Hidden {@code instanceCount=0} records are kept; this
 * pass does not compact them.
 */
final class SubmitGroup {
    static final int COMMAND_STRIDE = IndirectCommandPacking.STRIDE;

    final ChunkSectionLayer layer;
    final GpuBuffer vertexBuffer;
    final @Nullable GpuBuffer indexBuffer;
    final @Nullable IndexType indexType;
    final SubmitCommandList commands = new SubmitCommandList();
    @Nullable GpuBuffer gpuCommands;

    SubmitGroup(
            final ChunkSectionLayer layer,
            final GpuBuffer vertexBuffer,
            final @Nullable GpuBuffer indexBuffer,
            final @Nullable IndexType indexType) {
        this.layer = layer;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.indexType = indexType;
    }

    boolean matches(
            final ChunkSectionLayer layer,
            final GpuBuffer vertexBuffer,
            final @Nullable GpuBuffer indexBuffer,
            final @Nullable IndexType indexType) {
        return this.layer == layer
                && this.vertexBuffer == vertexBuffer
                && this.indexBuffer == indexBuffer
                && this.indexType == indexType;
    }

    int add(final RetainedSectionRecord.LayerSlot slot, final int sectionSlot) {
        int index = this.commands.add(slot, slot.firstIndex, slot.indexCount, slot.baseVertex, sectionSlot);
        slot.group = this;
        return index;
    }

    void updateImmutable(final RetainedSectionRecord.LayerSlot slot) {
        this.commands.updateImmutable(slot, slot.firstIndex, slot.indexCount, slot.baseVertex);
    }

    void setVisible(final RetainedSectionRecord.LayerSlot slot, final boolean visible) {
        this.commands.setVisible(slot, visible);
    }

    void remove(final RetainedSectionRecord.LayerSlot slot) {
        this.commands.remove(slot);
        slot.group = null;
    }

    boolean hasLiveDraws() {
        return this.commands.liveDraws() > 0;
    }

    int count() {
        return this.commands.count();
    }

    int maxIndexCount() {
        return this.commands.maxIndexCount();
    }

    long commandBufferBytes() {
        return this.gpuCommands == null ? 0L : this.gpuCommands.size();
    }

    void flushCommands(final CommandEncoder encoder, final RetainedUploadMetrics metrics) {
        if (this.commands.count() == 0) {
            return;
        }
        this.ensureGpu(metrics);
        if (this.gpuCommands == null) {
            return;
        }
        boolean structureDirty = this.commands.structureDirty();
        BitSetRuns.forEachRun(this.commands.dirty(), this.commands.count(), (from, to) -> {
            this.writeRange(encoder, from, to, metrics, structureDirty);
            metrics.commandDirtyRanges++;
        });
        this.commands.dirty().clear();
        this.commands.clearStructureDirty();
    }

    @Nullable GpuBufferSlice commandSlice() {
        if (this.gpuCommands == null || this.commands.count() <= 0) {
            return null;
        }
        return this.gpuCommands.slice(0L, (long)this.commands.count() * COMMAND_STRIDE);
    }

    @Nullable GpuBufferSlice commandSlice(final int index) {
        if (this.gpuCommands == null || index < 0 || index >= this.commands.count()) {
            return null;
        }
        return this.gpuCommands.slice((long)index * COMMAND_STRIDE, COMMAND_STRIDE);
    }

    private void writeRange(
            final CommandEncoder encoder,
            final int from,
            final int to,
            final RetainedUploadMetrics metrics,
            final boolean structureDirty) {
        int commandCount = to - from;
        int bytes = commandCount * COMMAND_STRIDE;
        ByteBuffer data = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (int i = from; i < to; i++) {
            IndirectCommandPacking.write(
                    data,
                    this.commands.indexCountAt(i),
                    this.commands.instanceCountAt(i),
                    this.commands.firstIndexAt(i),
                    this.commands.baseVertexAt(i),
                    this.commands.sectionSlotAt(i));
            metrics.commandRecordsChanged++;
            if (structureDirty) {
                metrics.immutableCommandWrites++;
            } else {
                metrics.visibilityCommandWrites++;
            }
        }
        data.flip();
        encoder.writeToBuffer(this.gpuCommands.slice((long)from * COMMAND_STRIDE, bytes), data);
        metrics.writeToBufferCalls++;
        metrics.writeToBufferBytes += bytes;
        metrics.commandBytesWritten += bytes;
    }

    private void ensureGpu(final RetainedUploadMetrics metrics) {
        int neededBytes = Math.max(1, this.commands.count()) * COMMAND_STRIDE;
        if (this.gpuCommands != null && this.gpuCommands.size() >= neededBytes) {
            return;
        }
        if (this.gpuCommands != null) {
            this.gpuCommands.close();
        }
        int alloc = Math.max(256 * COMMAND_STRIDE, Integer.highestOneBit(neededBytes - 1) << 1);
        GpuDevice device = RenderSystem.getDevice();
        this.gpuCommands = device.createBuffer(
                () -> "Ultima retained indirect " + this.layer,
                GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
                alloc);
        metrics.bufferReallocs++;
        metrics.commandBufferReallocs++;
        this.commands.markAllDirty();
    }

    void detachOwners() {
        for (int i = 0; i < this.commands.count(); i++) {
            if (this.commands.ownerAt(i) instanceof RetainedSectionRecord.LayerSlot slot) {
                slot.group = null;
                slot.alive = false;
            }
        }
        this.commands.detachAll();
    }

    void close() {
        this.detachOwners();
        if (this.gpuCommands != null) {
            this.gpuCommands.close();
            this.gpuCommands = null;
        }
    }
}
