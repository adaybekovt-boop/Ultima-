package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

/**
 * Persistent GPU rings for the shared ChunkSection header, per-batch tables,
 * and optional indirect commands.
 */
final class RetainedGpuResources implements AutoCloseable {
    static final int HEADER_BYTES = DynamicUniforms.CHUNK_SECTION_UBO_SIZE;
    static final int INDIRECT_STRIDE = VkDrawIndexedIndirectCommand.SIZEOF;
    private static final int HEADER_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
    private static final int BATCH_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
    private static final int INDIRECT_USAGE = GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_WRITE;

    private @Nullable MappableRingBuffer headerRing;
    private @Nullable MappableRingBuffer batchRing;
    private @Nullable MappableRingBuffer indirectRing;
    private final List<MappableRingBuffer> graveyard = new ArrayList<>();
    private int batchRingSlots;
    private int indirectRingSlots;
    private int headerAlignment;
    private int batchStride;
    private int nextBatchSlot;
    private int nextIndirectSlot;

    void beginFrame() {
        for (MappableRingBuffer old : this.graveyard) {
            old.close();
        }
        this.graveyard.clear();
        GpuDevice device = RenderSystem.getDevice();
        this.headerAlignment = Math.max(1, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        this.batchStride = align(RetainedTerrainPipelines.BATCH_UBO_BYTES, this.headerAlignment);
        if (this.headerRing == null) {
            this.headerRing = new MappableRingBuffer(
                    () -> "Ultima retained ChunkSection header",
                    HEADER_USAGE,
                    align(HEADER_BYTES, this.headerAlignment));
        }
        this.nextBatchSlot = 0;
        this.nextIndirectSlot = 0;
        if (this.batchRing != null) {
            this.batchRing.rotate();
        }
        if (this.indirectRing != null) {
            this.indirectRing.rotate();
        }
        this.headerRing.rotate();
    }

    GpuBufferSlice writeHeader(final Matrix4fc modelView, final int atlasWidth, final int atlasHeight) {
        ensureHeader();
        GpuBufferSlice slice = this.headerRing.currentBuffer().slice(0L, HEADER_BYTES);
        try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
            ByteBuffer buffer = view.data();
            buffer.order(ByteOrder.nativeOrder());
            buffer.position(0);
            Std140Builder.intoBuffer(buffer)
                    .putMat4f(modelView)
                    .putFloat(1.0F)
                    .putIVec2(atlasWidth, atlasHeight)
                    .putIVec3(0, 0, 0);
        }
        return slice;
    }

    GpuBufferSlice writeBatch(final OpaqueDrawBatch batch) {
        ensureBatchCapacity(this.nextBatchSlot + 1);
        long offset = (long)this.nextBatchSlot * this.batchStride;
        this.nextBatchSlot++;
        GpuBufferSlice slice = this.batchRing.currentBuffer().slice(offset, RetainedTerrainPipelines.BATCH_UBO_BYTES);
        try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
            ByteBuffer buffer = view.data();
            buffer.order(ByteOrder.nativeOrder());
            buffer.position(0);
            for (int i = 0; i < RetainedTerrainPipelines.BATCH_SIZE; i++) {
                if (i < batch.count) {
                    buffer.putInt(batch.originX[i]);
                    buffer.putInt(batch.originY[i]);
                    buffer.putInt(batch.originZ[i]);
                    buffer.putInt(SectionPacking.visibilityBits(batch.visibility[i]));
                } else {
                    buffer.putInt(0);
                    buffer.putInt(0);
                    buffer.putInt(0);
                    buffer.putInt(SectionPacking.visibilityBits(1.0F));
                }
            }
        }
        return slice;
    }

    GpuBufferSlice writeIndirect(final OpaqueDrawBatch batch) {
        int bytes = batch.count * INDIRECT_STRIDE;
        ensureIndirectCapacity(this.nextIndirectSlot + batch.count);
        long offset = (long)this.nextIndirectSlot * INDIRECT_STRIDE;
        this.nextIndirectSlot += batch.count;
        GpuBufferSlice slice = this.indirectRing.currentBuffer().slice(offset, bytes);
        try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
            ByteBuffer buffer = view.data();
            buffer.order(ByteOrder.nativeOrder());
            buffer.position(0);
            for (int i = 0; i < batch.count; i++) {
                buffer.putInt(batch.indexCount[i]);
                buffer.putInt(1);
                buffer.putInt(batch.firstIndex[i]);
                buffer.putInt(batch.baseVertex[i]);
                buffer.putInt(0);
            }
        }
        return slice;
    }

    private void ensureHeader() {
        if (this.headerRing == null) {
            throw new IllegalStateException("beginFrame was not called");
        }
    }

    private void ensureBatchCapacity(final int slots) {
        if (this.batchRing != null && slots <= this.batchRingSlots) {
            return;
        }
        int newSlots = Math.max(8, Integer.highestOneBit(Math.max(slots, 1) - 1) << 1);
        if (this.batchRing != null) {
            this.graveyard.add(this.batchRing);
        }
        this.batchRing = new MappableRingBuffer(
                () -> "Ultima retained section batch UBO",
                BATCH_USAGE,
                newSlots * this.batchStride);
        this.batchRingSlots = newSlots;
        this.nextBatchSlot = 0;
    }

    private void ensureIndirectCapacity(final int commands) {
        if (this.indirectRing != null && commands <= this.indirectRingSlots) {
            return;
        }
        int newSlots = Math.max(256, Integer.highestOneBit(Math.max(commands, 1) - 1) << 1);
        if (this.indirectRing != null) {
            this.graveyard.add(this.indirectRing);
        }
        this.indirectRing = new MappableRingBuffer(
                () -> "Ultima retained indirect commands",
                INDIRECT_USAGE,
                newSlots * INDIRECT_STRIDE);
        this.indirectRingSlots = newSlots;
        this.nextIndirectSlot = 0;
    }

    private static int align(final int size, final int alignment) {
        return (size + alignment - 1) / alignment * alignment;
    }

    @Override
    public void close() {
        for (MappableRingBuffer old : this.graveyard) {
            old.close();
        }
        this.graveyard.clear();
        if (this.headerRing != null) {
            this.headerRing.close();
        }
        if (this.batchRing != null) {
            this.batchRing.close();
        }
        if (this.indirectRing != null) {
            this.indirectRing.close();
        }
        this.headerRing = null;
        this.batchRing = null;
        this.indirectRing = null;
    }
}
