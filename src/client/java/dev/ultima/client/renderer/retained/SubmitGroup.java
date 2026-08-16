package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.util.BitSetRuns;
import dev.ultima.util.IndirectCommandPacking;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/**
 * One persistent submit group: same layer, vertex buffer, and index buffer.
 * Indirect commands live on the GPU and are patched in place.
 */
final class SubmitGroup {
    static final int COMMAND_STRIDE = IndirectCommandPacking.STRIDE;

    final ChunkSectionLayer layer;
    final GpuBuffer vertexBuffer;
    final @Nullable GpuBuffer indexBuffer;
    final @Nullable IndexType indexType;

    int count;
    int[] firstIndex = new int[16];
    int[] indexCount = new int[16];
    int[] baseVertex = new int[16];
    int[] sectionSlot = new int[16];
    int[] instanceCount = new int[16];
    RetainedSectionRecord.LayerSlot[] owners = new RetainedSectionRecord.LayerSlot[16];
    final BitSet dirty = new BitSet();
    boolean structureDirty;
    int maxIndexCount;
    int liveDraws;
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
        this.ensureCapacity(this.count + 1);
        int index = this.count++;
        this.firstIndex[index] = slot.firstIndex;
        this.indexCount[index] = slot.indexCount;
        this.baseVertex[index] = slot.baseVertex;
        this.sectionSlot[index] = sectionSlot;
        this.instanceCount[index] = 1;
        this.owners[index] = slot;
        this.dirty.set(index);
        this.structureDirty = true;
        if (slot.indexCount > this.maxIndexCount) {
            this.maxIndexCount = slot.indexCount;
        }
        slot.group = this;
        slot.commandIndex = index;
        slot.instanceCount = 1;
        this.liveDraws++;
        return index;
    }

    void updateImmutable(final RetainedSectionRecord.LayerSlot slot) {
        int index = slot.commandIndex;
        if (index < 0 || index >= this.count) {
            return;
        }
        this.firstIndex[index] = slot.firstIndex;
        this.indexCount[index] = slot.indexCount;
        this.baseVertex[index] = slot.baseVertex;
        this.dirty.set(index);
        this.structureDirty = true;
        if (slot.indexCount > this.maxIndexCount) {
            this.maxIndexCount = slot.indexCount;
        }
    }

    void setVisible(final RetainedSectionRecord.LayerSlot slot, final boolean visible) {
        int index = slot.commandIndex;
        if (index < 0 || index >= this.count) {
            return;
        }
        int value = visible ? 1 : 0;
        if (this.instanceCount[index] == value) {
            slot.instanceCount = value;
            return;
        }
        this.instanceCount[index] = value;
        slot.instanceCount = value;
        this.liveDraws += visible ? 1 : -1;
        this.dirty.set(index);
    }

    void remove(final RetainedSectionRecord.LayerSlot slot) {
        int index = slot.commandIndex;
        if (index < 0 || index >= this.count) {
            slot.group = null;
            slot.commandIndex = -1;
            slot.instanceCount = 0;
            return;
        }
        if (this.instanceCount[index] > 0) {
            this.liveDraws--;
        }
        int last = this.count - 1;
        if (index != last) {
            this.firstIndex[index] = this.firstIndex[last];
            this.indexCount[index] = this.indexCount[last];
            this.baseVertex[index] = this.baseVertex[last];
            this.sectionSlot[index] = this.sectionSlot[last];
            this.instanceCount[index] = this.instanceCount[last];
            this.owners[index] = this.owners[last];
            if (this.owners[index] != null) {
                this.owners[index].commandIndex = index;
            }
            this.dirty.set(index);
        }
        this.owners[last] = null;
        this.count--;
        this.structureDirty = true;
        this.dirty.clear(last);
        slot.group = null;
        slot.commandIndex = -1;
        slot.instanceCount = 0;
    }

    boolean hasLiveDraws() {
        return this.liveDraws > 0;
    }

    void flushCommands(final CommandEncoder encoder, final RetainedUploadMetrics metrics) {
        if (this.count == 0) {
            return;
        }
        this.ensureGpu(metrics);
        if (this.gpuCommands == null) {
            return;
        }
        BitSetRuns.forEachRun(this.dirty, this.count, (from, to) -> {
            this.writeRange(encoder, from, to, metrics);
            metrics.dirtyRanges++;
        });
        this.dirty.clear();
        this.structureDirty = false;
    }

    @Nullable GpuBufferSlice commandSlice() {
        if (this.gpuCommands == null || this.count <= 0) {
            return null;
        }
        return this.gpuCommands.slice(0L, (long)this.count * COMMAND_STRIDE);
    }

    @Nullable GpuBufferSlice commandSlice(final int index) {
        if (this.gpuCommands == null || index < 0 || index >= this.count) {
            return null;
        }
        return this.gpuCommands.slice((long)index * COMMAND_STRIDE, COMMAND_STRIDE);
    }

    private void writeRange(
            final CommandEncoder encoder,
            final int from,
            final int to,
            final RetainedUploadMetrics metrics) {
        int commands = to - from;
        int bytes = commands * COMMAND_STRIDE;
        ByteBuffer data = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        boolean anyImmutable = this.structureDirty;
        for (int i = from; i < to; i++) {
            IndirectCommandPacking.write(
                    data,
                    this.indexCount[i],
                    this.instanceCount[i],
                    this.firstIndex[i],
                    this.baseVertex[i],
                    this.sectionSlot[i]);
            metrics.commandRecordsChanged++;
            if (anyImmutable) {
                metrics.immutableCommandWrites++;
            } else {
                metrics.visibilityCommandWrites++;
            }
        }
        data.flip();
        encoder.writeToBuffer(this.gpuCommands.slice((long)from * COMMAND_STRIDE, bytes), data);
        metrics.writeToBufferCalls++;
        metrics.commandBytesWritten += bytes;
    }

    private void ensureCapacity(final int needed) {
        if (needed <= this.firstIndex.length) {
            return;
        }
        int size = Integer.highestOneBit(needed - 1) << 1;
        this.firstIndex = Arrays.copyOf(this.firstIndex, size);
        this.indexCount = Arrays.copyOf(this.indexCount, size);
        this.baseVertex = Arrays.copyOf(this.baseVertex, size);
        this.sectionSlot = Arrays.copyOf(this.sectionSlot, size);
        this.instanceCount = Arrays.copyOf(this.instanceCount, size);
        this.owners = Arrays.copyOf(this.owners, size);
    }

    private void ensureGpu(final RetainedUploadMetrics metrics) {
        int neededBytes = Math.max(1, this.count) * COMMAND_STRIDE;
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
        this.dirty.set(0, this.count);
        this.structureDirty = true;
    }

    void detachOwners() {
        for (int i = 0; i < this.count; i++) {
            RetainedSectionRecord.LayerSlot owner = this.owners[i];
            if (owner != null) {
                owner.group = null;
                owner.commandIndex = -1;
                owner.instanceCount = 0;
                owner.alive = false;
                this.owners[i] = null;
            }
        }
        this.count = 0;
        this.liveDraws = 0;
        this.dirty.clear();
        this.structureDirty = true;
    }

    void close() {
        this.detachOwners();
        if (this.gpuCommands != null) {
            this.gpuCommands.close();
            this.gpuCommands = null;
        }
    }
}
