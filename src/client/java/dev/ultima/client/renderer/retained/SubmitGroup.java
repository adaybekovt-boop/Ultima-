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
 * <p>CPU swap-remove lives in {@link SubmitCommandList}. Hidden commands are normally
 * retained as {@code instanceCount=0}; bounded compaction is scheduled by one submit
 * and applied at the next frame flush, strictly between opaque render passes. The GPU
 * command buffer is replaced transactionally instead of compacted in place.
 */
final class SubmitGroup {
    static final int COMMAND_STRIDE = IndirectCommandPacking.STRIDE;

    final ChunkSectionLayer layer;
    final GpuBuffer vertexBuffer;
    final @Nullable GpuBuffer indexBuffer;
    final @Nullable IndexType indexType;
    final SubmitCommandList commands = new SubmitCommandList();
    @Nullable GpuBuffer gpuCommands;
    private boolean compactionPending;

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
        if (this.commands.shouldCompactBounded()) {
            // We are already in the submit path for this frame. Do not mutate the
            // command list or GPU buffer now; compact at the next pre-pass flush.
            this.compactionPending = true;
        }
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
        if (this.compactionPending) {
            this.compactionPending = false;
            this.compactPendingBetweenPasses(metrics);
        }
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

    /**
     * Apply the Prompt #2.4 bounded-growth policy between render passes. A replacement
     * GPU buffer is prepared before CPU indices are compacted; only after compaction
     * succeeds is the group pointer swapped and the old buffer retired. The current
     * frame then uploads the compacted CPU list to the replacement before drawing.
     */
    private boolean compactPendingBetweenPasses(final RetainedUploadMetrics metrics) {
        if (!this.commands.shouldCompactBounded()) {
            return false;
        }

        GpuBuffer replacement = null;
        if (this.commands.liveDraws() > 0) {
            replacement = this.createCommandBuffer(this.commands.liveDraws());
        }

        final GpuBuffer preparedReplacement = replacement;
        try {
            SubmitCommandList.CompactionResult result = this.commands.compactHidden(owner -> {
                if (owner instanceof RetainedSectionRecord.LayerSlot slot && slot.group == this) {
                    slot.group = null;
                }
            });
            if (!result.compacted()) {
                if (preparedReplacement != null) {
                    preparedReplacement.close();
                }
                return false;
            }

            GpuBuffer old = this.gpuCommands;
            this.gpuCommands = preparedReplacement;
            this.commands.markAllDirty();
            if (old != null) {
                old.close();
            }
            metrics.bufferReallocs++;
            metrics.commandBufferReallocs++;
            RetainedCompactionDebug.recordSuccessfulCompaction();
            return true;
        } catch (RuntimeException error) {
            if (preparedReplacement != null) {
                preparedReplacement.close();
            }
            throw error;
        }
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
        GpuBuffer replacement = this.createCommandBuffer(this.commands.count());
        GpuBuffer old = this.gpuCommands;
        this.gpuCommands = replacement;
        if (old != null) {
            old.close();
        }
        metrics.bufferReallocs++;
        metrics.commandBufferReallocs++;
        this.commands.markAllDirty();
    }

    private GpuBuffer createCommandBuffer(final int commandCount) {
        int neededBytes = Math.max(1, commandCount) * COMMAND_STRIDE;
        int alloc = Math.max(256 * COMMAND_STRIDE, Integer.highestOneBit(neededBytes - 1) << 1);
        GpuDevice device = RenderSystem.getDevice();
        return device.createBuffer(
                () -> "Ultima retained indirect " + this.layer,
                GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
                alloc);
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
