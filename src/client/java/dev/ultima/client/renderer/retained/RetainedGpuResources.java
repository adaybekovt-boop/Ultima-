package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ultima.util.BitSetRuns;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Persistent GPU section table and shared ChunkSection header.
 * Updates use {@code writeToBuffer} dirty ranges — no map/unmap, no ring fences.
 */
final class RetainedGpuResources implements AutoCloseable {
    static final int HEADER_BYTES = DynamicUniforms.CHUNK_SECTION_UBO_SIZE;
    static final int SLOT_BYTES = 16;
    static final GpuFormat TABLE_FORMAT = GpuFormat.RGBA32_SINT;

    private @Nullable GpuBuffer header;
    private @Nullable GpuBuffer sectionTable;
    private int[] tableCpu = new int[0];
    private final BitSet tableDirty = new BitSet();
    private int tableSlots;
    private final Matrix4f lastHeaderView = new Matrix4f();
    private int lastAtlasW = Integer.MIN_VALUE;
    private int lastAtlasH = Integer.MIN_VALUE;
    private boolean headerDirty = true;
    final RetainedUploadMetrics metrics = new RetainedUploadMetrics();
    final RetainedGpuTimers timers = new RetainedGpuTimers();

    void beginFrame() {
        this.metrics.reset();
        this.timers.poll(this.metrics);
    }

    void ensureSectionSlots(final int slots) {
        if (slots <= this.tableSlots) {
            return;
        }
        int newSlots = Math.max(256, Integer.highestOneBit(slots - 1) << 1);
        this.tableCpu = Arrays.copyOf(this.tableCpu, newSlots * 4);
        if (this.sectionTable != null) {
            this.sectionTable.close();
            this.sectionTable = null;
            this.metrics.bufferReallocs++;
        }
        GpuDevice device = RenderSystem.getDevice();
        this.sectionTable = device.createBuffer(
                () -> "Ultima retained section table",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                (long)newSlots * SLOT_BYTES);
        this.tableSlots = newSlots;
        this.tableDirty.set(0, newSlots);
    }

    void markSection(final int slot, final int originX, final int originY, final int originZ, final float visibility) {
        this.ensureSectionSlots(slot + 1);
        int base = slot * 4;
        int visBits = SectionPacking.visibilityBits(visibility);
        if (this.tableCpu[base] == originX
                && this.tableCpu[base + 1] == originY
                && this.tableCpu[base + 2] == originZ
                && this.tableCpu[base + 3] == visBits) {
            return;
        }
        this.tableCpu[base] = originX;
        this.tableCpu[base + 1] = originY;
        this.tableCpu[base + 2] = originZ;
        this.tableCpu[base + 3] = visBits;
        this.tableDirty.set(slot);
    }

    boolean markHeader(final Matrix4fc modelView, final int atlasWidth, final int atlasHeight) {
        if (!this.headerDirty
                && this.lastAtlasW == atlasWidth
                && this.lastAtlasH == atlasHeight
                && this.lastHeaderView.equals(modelView, 0.0F)) {
            return false;
        }
        this.lastHeaderView.set(modelView);
        this.lastAtlasW = atlasWidth;
        this.lastAtlasH = atlasHeight;
        this.headerDirty = true;
        return true;
    }

    void flush(final CommandEncoder encoder) {
        this.flushHeader(encoder);
        this.flushSectionTable(encoder);
    }

    GpuBuffer headerBuffer() {
        this.ensureHeader();
        return this.header;
    }

    GpuBuffer sectionTableBuffer() {
        this.ensureSectionSlots(1);
        return this.sectionTable;
    }

    private void flushHeader(final CommandEncoder encoder) {
        if (!this.headerDirty) {
            return;
        }
        this.ensureHeader();
        ByteBuffer data = ByteBuffer.allocateDirect(HEADER_BYTES).order(ByteOrder.nativeOrder());
        Std140Builder.intoBuffer(data)
                .putMat4f(this.lastHeaderView)
                .putFloat(1.0F)
                .putIVec2(this.lastAtlasW, this.lastAtlasH)
                .putIVec3(0, 0, 0);
        data.flip();
        encoder.writeToBuffer(this.header.slice(0L, HEADER_BYTES), data);
        this.metrics.writeToBufferCalls++;
        this.metrics.metadataBytesWritten += HEADER_BYTES;
        this.metrics.headerWrites++;
        this.headerDirty = false;
    }

    private void flushSectionTable(final CommandEncoder encoder) {
        if (this.sectionTable == null) {
            return;
        }
        BitSetRuns.forEachRun(this.tableDirty, this.tableSlots, (from, to) -> {
            int slots = to - from;
            int bytes = slots * SLOT_BYTES;
            ByteBuffer data = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            data.asIntBuffer().put(this.tableCpu, from * 4, slots * 4);
            data.limit(bytes);
            encoder.writeToBuffer(this.sectionTable.slice((long)from * SLOT_BYTES, bytes), data);
            this.metrics.writeToBufferCalls++;
            this.metrics.metadataBytesWritten += bytes;
            this.metrics.sectionTableSlotsWritten += slots;
            this.metrics.dirtyRanges++;
        });
        this.tableDirty.clear();
    }

    private void ensureHeader() {
        if (this.header != null) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        this.header = device.createBuffer(
                () -> "Ultima retained ChunkSection header",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                HEADER_BYTES);
        this.headerDirty = true;
        this.metrics.bufferReallocs++;
    }

    @Override
    public void close() {
        this.timers.close();
        if (this.header != null) {
            this.header.close();
            this.header = null;
        }
        if (this.sectionTable != null) {
            this.sectionTable.close();
            this.sectionTable = null;
        }
        this.tableSlots = 0;
        this.tableCpu = new int[0];
        this.tableDirty.clear();
        this.headerDirty = true;
    }
}
