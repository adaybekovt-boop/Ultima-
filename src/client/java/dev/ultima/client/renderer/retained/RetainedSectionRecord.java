package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ultima.retained.IndexedCommandOwner;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Persistent per-section CPU record. Not allocated per draw.
 */
public final class RetainedSectionRecord {
    public int slot;
    public long sectionNode = Long.MIN_VALUE;
    public int originX;
    public int originY;
    public int originZ;
    public float visibility = 1.0F;
    public int meshId;
    public int generation;
    public boolean visibleThisFrame;
    /**
     * Bit 0: world transform is identical across frames (static terrain). Camera
     * motion still produces screen-space velocity; do not invent entity motion
     * from this flag.
     */
    public static final int FLAG_STATIC_WORLD_TRANSFORM = 1;
    public int temporalFlags = FLAG_STATIC_WORLD_TRANSFORM;
    public final LayerSlot solid = new LayerSlot();
    public final LayerSlot cutout = new LayerSlot();

    public LayerSlot layer(final ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.CUTOUT ? this.cutout : this.solid;
    }

    public void resetIdentity(final int slot, final long sectionNode, final int originX, final int originY, final int originZ) {
        this.slot = slot;
        this.sectionNode = sectionNode;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.visibility = 1.0F;
        this.meshId = 0;
        this.generation++;
        this.visibleThisFrame = false;
        this.temporalFlags = FLAG_STATIC_WORLD_TRANSFORM;
        this.solid.clear();
        this.cutout.clear();
    }

    public static final class LayerSlot implements IndexedCommandOwner {
        public boolean alive;
        public int meshId;
        public @Nullable GpuBuffer vertexBuffer;
        public @Nullable GpuBuffer indexBuffer;
        public @Nullable IndexType indexType;
        public int firstIndex;
        public int indexCount;
        public int baseVertex;
        public int commandGeneration;
        public @Nullable SubmitGroup group;
        public int commandIndex = -1;
        public int instanceCount;
        public boolean seenThisFrame;

        public boolean capture(
                final SectionMesh.SectionDraw draw,
                final SectionRenderDispatcher.RenderSectionBufferSlice slice,
                final VertexFormat vertexFormat,
                final int meshId) {
            GpuBuffer vertex = slice.vertexBuffer();
            GpuBuffer index = draw.hasCustomIndexBuffer() ? slice.indexBuffer() : null;
            IndexType indexType = draw.hasCustomIndexBuffer() ? draw.indexType() : null;
            int firstIndex = 0;
            if (draw.hasCustomIndexBuffer()) {
                if (index == null || indexType == null) {
                    return false;
                }
                firstIndex = (int)(slice.indexBufferOffset() / indexType.bytes);
            }
            int baseVertex = (int)(slice.vertexBufferOffset() / vertexFormat.getVertexSize());
            boolean changed = !this.alive
                    || this.meshId != meshId
                    || this.vertexBuffer != vertex
                    || this.indexBuffer != index
                    || this.indexType != indexType
                    || this.firstIndex != firstIndex
                    || this.indexCount != draw.indexCount()
                    || this.baseVertex != baseVertex;
            this.alive = true;
            this.meshId = meshId;
            this.vertexBuffer = vertex;
            this.indexBuffer = index;
            this.indexType = indexType;
            this.firstIndex = firstIndex;
            this.indexCount = draw.indexCount();
            this.baseVertex = baseVertex;
            if (changed) {
                this.commandGeneration++;
            }
            return changed;
        }

        public void clear() {
            if (this.group != null) {
                this.group.remove(this);
            }
            this.alive = false;
            this.meshId = 0;
            this.vertexBuffer = null;
            this.indexBuffer = null;
            this.indexType = null;
            this.firstIndex = 0;
            this.indexCount = 0;
            this.baseVertex = 0;
            this.commandGeneration++;
            this.instanceCount = 0;
        }

        @Override
        public int getCommandIndex() {
            return this.commandIndex;
        }

        @Override
        public void onCommandAttached(final int commandIndex, final int instanceCount) {
            this.commandIndex = commandIndex;
            this.instanceCount = instanceCount;
        }

        @Override
        public void onCommandMoved(final int newCommandIndex) {
            this.commandIndex = newCommandIndex;
        }

        @Override
        public void onCommandDetached() {
            this.commandIndex = -1;
            this.instanceCount = 0;
        }
    }
}
