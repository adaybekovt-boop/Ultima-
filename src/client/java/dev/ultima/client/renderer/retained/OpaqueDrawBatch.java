package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/**
 * One GPU submission group: same pipeline, vertex buffer, and index buffer.
 */
public final class OpaqueDrawBatch {
    public ChunkSectionLayer layer;
    public GpuBuffer vertexBuffer;
    public @Nullable GpuBuffer indexBuffer;
    public @Nullable IndexType indexType;
    public int count;
    public int maxIndexCount;
    public final int[] originX;
    public final int[] originY;
    public final int[] originZ;
    public final float[] visibility;
    public final int[] firstIndex;
    public final int[] indexCount;
    public final int[] baseVertex;

    public OpaqueDrawBatch(final int capacity) {
        this.originX = new int[capacity];
        this.originY = new int[capacity];
        this.originZ = new int[capacity];
        this.visibility = new float[capacity];
        this.firstIndex = new int[capacity];
        this.indexCount = new int[capacity];
        this.baseVertex = new int[capacity];
    }

    public void begin(
            final ChunkSectionLayer layer,
            final GpuBuffer vertexBuffer,
            final @Nullable GpuBuffer indexBuffer,
            final @Nullable IndexType indexType) {
        this.layer = layer;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.indexType = indexType;
        this.count = 0;
        this.maxIndexCount = 0;
    }

    public boolean isCompatible(
            final ChunkSectionLayer layer,
            final GpuBuffer vertexBuffer,
            final @Nullable GpuBuffer indexBuffer,
            final @Nullable IndexType indexType) {
        return this.layer == layer
                && this.vertexBuffer == vertexBuffer
                && this.indexBuffer == indexBuffer
                && this.indexType == indexType;
    }

    public void add(
            final int originX,
            final int originY,
            final int originZ,
            final float visibility,
            final int firstIndex,
            final int indexCount,
            final int baseVertex) {
        int i = this.count++;
        this.originX[i] = originX;
        this.originY[i] = originY;
        this.originZ[i] = originZ;
        this.visibility[i] = visibility;
        this.firstIndex[i] = firstIndex;
        this.indexCount[i] = indexCount;
        this.baseVertex[i] = baseVertex;
        if (indexCount > this.maxIndexCount) {
            this.maxIndexCount = indexCount;
        }
    }
}
