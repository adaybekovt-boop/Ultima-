package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ultima.client.metrics.TerrainFrameMetrics;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opaque retained producer. Translucent terrain is still built as vanilla draws.
 */
public final class RetainedTerrainRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-retained-terrain");
    private static final RetainedTerrainRenderer INSTANCE = new RetainedTerrainRenderer();

    private final RetainedGpuResources gpu = new RetainedGpuResources();
    private RetainedSectionRecord[] sections = new RetainedSectionRecord[0];
    private final List<OpaqueDrawBatch> batches = new ArrayList<>();
    private final List<OpaqueDrawBatch> batchPool = new ArrayList<>();
    private @Nullable GpuTextureView blockAtlas;
    private final Matrix4f headerModelView = new Matrix4f();
    private boolean opaqueReady;
    private boolean failedOpen;
    private int frameVisibleSections;
    private int frameSectionLayers;
    private int frameCommandRebuilds;
    private int frameMetadataUpdates;
    private int maxBatchSize = RetainedTerrainPipelines.BATCH_SIZE;
    private long lastOpaqueFingerprint;
    private int lastOpaqueDrawCount;
    private boolean tryingReuse;
    private boolean reuseFailed;
    private long frameFingerprint;
    private int frameOpaqueDraws;

    public static RetainedTerrainRenderer get() {
        return INSTANCE;
    }

    public boolean isOpaqueReady() {
        return this.opaqueReady && !this.failedOpen;
    }

    public void failOpen(final String reason, final @Nullable Throwable error) {
        if (!this.failedOpen) {
            if (error != null) {
                LOGGER.warn("Retained terrain failed open to vanilla: {}", reason, error);
            } else {
                LOGGER.warn("Retained terrain failed open to vanilla: {}", reason);
            }
        }
        this.failedOpen = true;
        this.opaqueReady = false;
    }

    public void reset() {
        this.gpu.close();
        this.sections = new RetainedSectionRecord[0];
        this.batches.clear();
        this.batchPool.clear();
        this.opaqueReady = false;
        this.failedOpen = false;
        this.lastOpaqueFingerprint = 0L;
        this.lastOpaqueDrawCount = 0;
        RetainedTerrainPipelines.invalidate();
        RetainedTerrainCapabilities.invalidate();
    }

    public @Nullable ChunkSectionsToRender prepare(
            final LevelRenderer levelRenderer,
            final Matrix4fc modelView,
            final ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
            final @Nullable SectionRenderDispatcher dispatcher,
            final TextureManager textureManager) {
        this.opaqueReady = false;
        if (this.failedOpen || dispatcher == null || !RetainedTerrainCapabilities.available()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (SharedConstants.DEBUG_HOTKEYS && minecraft.wireframe) {
            return null;
        }
        if (!RetainedTerrainPipelines.ensureCompiled()) {
            if (RetainedTerrainPipelines.compileFailed()) {
                this.failOpen("pipeline compile", null);
            }
            return null;
        }

        TerrainFrameMetrics.beginCommand();
        try {
            this.gpu.beginFrame();
            this.maxBatchSize = RetainedTerrainCapabilities.maxDrawsPerBatch();
            this.blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            this.headerModelView.set(modelView);
            int atlasW = this.blockAtlas.getWidth(0);
            int atlasH = this.blockAtlas.getHeight(0);
            this.frameVisibleSections = visibleSections.size();
            this.frameSectionLayers = 0;
            this.frameCommandRebuilds = 0;
            this.frameMetadataUpdates = 0;
            this.tryingReuse = !this.batches.isEmpty();
            this.reuseFailed = false;
            this.frameFingerprint = 0xcbf29ce484222325L;
            this.frameOpaqueDraws = 0;

            EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> translucent =
                    new EnumMap<>(ChunkSectionLayer.class);
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                translucent.put(layer, new Int2ObjectOpenHashMap<>());
            }
            List<DynamicUniforms.ChunkSectionInfo> translucentInfos = new ArrayList<>();
            int largestIndexCount = 0;

            dispatcher.lock();
            try {
                long now = Util.getMillis();
                for (int i = 0; i < visibleSections.size(); i++) {
                    SectionRenderDispatcher.RenderSection section = visibleSections.get(i);
                    this.captureOpaque(section, dispatcher, now);
                    largestIndexCount = Math.max(
                            largestIndexCount,
                            this.captureTranslucent(section, dispatcher, now, atlasW, atlasH, translucent, translucentInfos));
                }
            } finally {
                dispatcher.unlock();
            }

            this.frameFingerprint ^= (long)visibleSections.size() * 0x9E3779B97F4A7C15L;
            if (!this.tryingReuse
                    || this.reuseFailed
                    || this.frameFingerprint != this.lastOpaqueFingerprint
                    || this.frameOpaqueDraws != this.lastOpaqueDrawCount) {
                this.recycleBatches();
                this.rebuildOpaqueBatches(visibleSections);
                TerrainFrameMetrics.setCommandBatchesReused(false);
            } else {
                TerrainFrameMetrics.setCommandBatchesReused(true);
            }
            this.lastOpaqueFingerprint = this.frameFingerprint;
            this.lastOpaqueDrawCount = this.frameOpaqueDraws;

            GpuBufferSlice[] translucentUbos = RenderSystem.getDynamicUniforms()
                    .writeChunkSections(translucentInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
            this.opaqueReady = true;
            TerrainFrameMetrics.setRetainedActive(true, RetainedTerrainCapabilities.describeSubmitMode());
            TerrainFrameMetrics.recordVisible(
                    this.frameVisibleSections,
                    this.frameSectionLayers,
                    this.totalOpaqueDraws(),
                    this.totalOpaqueDraws() + translucentInfos.size());
            TerrainFrameMetrics.addCommandRebuilds(this.frameCommandRebuilds);
            TerrainFrameMetrics.addMetadataUpdates(this.frameMetadataUpdates);
            return new ChunkSectionsToRender(this.blockAtlas, translucent, largestIndexCount, translucentUbos);
        } catch (RuntimeException e) {
            this.failOpen("prepare", e);
            return null;
        } finally {
            TerrainFrameMetrics.endCommand();
        }
    }

    public void submitOpaque(final GpuSampler sampler) {
        if (!this.isOpaqueReady() || this.blockAtlas == null) {
            return;
        }
        try {
            GpuBufferSlice header = this.gpu.writeHeader(
                    this.headerModelView,
                    this.blockAtlas.getWidth(0),
                    this.blockAtlas.getHeight(0));
            OpaqueTerrainSubmitter.submit(this.batches, header, this.gpu, this.blockAtlas, sampler);
        } catch (RuntimeException e) {
            this.failOpen("submit", e);
        } finally {
            this.opaqueReady = false;
        }
    }

    private void captureOpaque(
            final SectionRenderDispatcher.RenderSection section,
            final SectionRenderDispatcher dispatcher,
            final long now) {
        RetainedSectionRecord record = this.recordFor(section);
        SectionMesh mesh = section.getSectionMesh();
        if (mesh == CompiledSectionMesh.UNCOMPILED || mesh == CompiledSectionMesh.EMPTY || !mesh.hasRenderableLayers()) {
            if (record.solid.alive || record.cutout.alive) {
                record.solid.clear();
                record.cutout.clear();
                this.frameCommandRebuilds++;
                this.reuseFailed = true;
            }
            this.frameFingerprint = mixFingerprint(
                    this.frameFingerprint,
                    record.slot,
                    record.sectionNode,
                    false,
                    record.solid.commandGeneration,
                    false,
                    record.cutout.commandGeneration);
            return;
        }
        BlockPos origin = section.getRenderOrigin();
        float visibility = section.getVisibility(now);
        int meshId = System.identityHashCode(mesh);
        if (record.originX != origin.getX()
                || record.originY != origin.getY()
                || record.originZ != origin.getZ()
                || record.visibility != visibility) {
            record.originX = origin.getX();
            record.originY = origin.getY();
            record.originZ = origin.getZ();
            record.visibility = visibility;
            this.frameMetadataUpdates++;
        }
        record.meshId = meshId;
        record.visibleThisFrame = true;
        record.temporalFlags = RetainedSectionRecord.FLAG_STATIC_WORLD_TRANSFORM;
        this.captureLayer(record, dispatcher, mesh, ChunkSectionLayer.SOLID, meshId);
        this.captureLayer(record, dispatcher, mesh, ChunkSectionLayer.CUTOUT, meshId);
        this.frameFingerprint = mixFingerprint(
                this.frameFingerprint,
                record.slot,
                record.sectionNode,
                record.solid.alive,
                record.solid.commandGeneration,
                record.cutout.alive,
                record.cutout.commandGeneration);
    }

    private void captureLayer(
            final RetainedSectionRecord record,
            final SectionRenderDispatcher dispatcher,
            final SectionMesh mesh,
            final ChunkSectionLayer layer,
            final int meshId) {
        SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
        SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, layer);
        RetainedSectionRecord.LayerSlot slot = record.layer(layer);
        if (slice == null || draw == null || (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) {
            if (slot.alive) {
                slot.clear();
                this.frameCommandRebuilds++;
                this.reuseFailed = true;
            }
            return;
        }
        VertexFormat format = layer.pipeline().getVertexFormatBinding(0);
        if (slot.capture(draw, slice, format, meshId)) {
            this.frameCommandRebuilds++;
            this.reuseFailed = true;
        }
        this.frameSectionLayers++;
        this.frameOpaqueDraws++;
        if (this.tryingReuse && !this.reuseFailed) {
            if (!this.patchBatchMetadata(record, slot)) {
                this.reuseFailed = true;
            }
        }
    }

    private void appendBatch(
            final ChunkSectionLayer layer,
            final RetainedSectionRecord record,
            final RetainedSectionRecord.LayerSlot slot) {
        OpaqueDrawBatch batch = null;
        if (!this.batches.isEmpty()) {
            OpaqueDrawBatch last = this.batches.get(this.batches.size() - 1);
            if (last.isCompatible(layer, slot.vertexBuffer, slot.indexBuffer, slot.indexType)
                    && last.count < this.maxBatchSize) {
                batch = last;
            }
        }
        if (batch == null) {
            batch = this.allocateBatch();
            batch.begin(layer, java.util.Objects.requireNonNull(slot.vertexBuffer), slot.indexBuffer, slot.indexType);
            this.batches.add(batch);
        }
        batch.add(record.originX, record.originY, record.originZ, record.visibility, slot.firstIndex, slot.indexCount, slot.baseVertex);
        slot.batchIndex = this.batches.size() - 1;
        slot.drawIndex = batch.count - 1;
    }

    private boolean patchBatchMetadata(final RetainedSectionRecord record, final RetainedSectionRecord.LayerSlot slot) {
        if (slot.batchIndex < 0 || slot.batchIndex >= this.batches.size()) {
            return false;
        }
        OpaqueDrawBatch batch = this.batches.get(slot.batchIndex);
        if (slot.drawIndex < 0 || slot.drawIndex >= batch.count) {
            return false;
        }
        batch.patchMetadata(slot.drawIndex, record.originX, record.originY, record.originZ, record.visibility);
        return true;
    }

    private void rebuildOpaqueBatches(final ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections) {
        for (int i = 0; i < visibleSections.size(); i++) {
            SectionRenderDispatcher.RenderSection section = visibleSections.get(i);
            int slot = section.index;
            if (slot < 0 || slot >= this.sections.length) {
                continue;
            }
            RetainedSectionRecord record = this.sections[slot];
            if (record == null || !record.visibleThisFrame) {
                continue;
            }
            if (record.solid.alive) {
                this.appendBatch(ChunkSectionLayer.SOLID, record, record.solid);
            }
            if (record.cutout.alive) {
                this.appendBatch(ChunkSectionLayer.CUTOUT, record, record.cutout);
            }
        }
    }

    private static long mixFingerprint(
            long hash,
            final int slot,
            final long sectionNode,
            final boolean solidAlive,
            final int solidGeneration,
            final boolean cutoutAlive,
            final int cutoutGeneration) {
        hash ^= slot;
        hash *= 0x100000001b3L;
        hash ^= sectionNode;
        hash *= 0x100000001b3L;
        hash ^= (solidAlive ? 1L : 0L) | (cutoutAlive ? 2L : 0L);
        hash *= 0x100000001b3L;
        hash ^= solidGeneration;
        hash *= 0x100000001b3L;
        hash ^= cutoutGeneration;
        hash *= 0x100000001b3L;
        return hash;
    }

    private int captureTranslucent(
            final SectionRenderDispatcher.RenderSection section,
            final SectionRenderDispatcher dispatcher,
            final long now,
            final int atlasW,
            final int atlasH,
            final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups,
            final List<DynamicUniforms.ChunkSectionInfo> sectionInfos) {
        SectionMesh mesh = section.getSectionMesh();
        SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.TRANSLUCENT);
        SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.TRANSLUCENT);
        if (slice == null || draw == null || (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) {
            return 0;
        }
        BlockPos origin = section.getRenderOrigin();
        int uboIndex = sectionInfos.size();
        sectionInfos.add(new DynamicUniforms.ChunkSectionInfo(
                this.headerModelView,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                section.getVisibility(now),
                atlasW,
                atlasH));
        int firstIndex = 0;
        com.mojang.blaze3d.buffers.GpuBuffer indexBuffer = null;
        com.mojang.blaze3d.IndexType indexType = null;
        int largest = 0;
        if (!draw.hasCustomIndexBuffer()) {
            largest = draw.indexCount();
        } else {
            indexBuffer = slice.indexBuffer();
            indexType = draw.indexType();
            firstIndex = (int)(slice.indexBufferOffset() / indexType.bytes);
        }
        VertexFormat format = ChunkSectionLayer.TRANSLUCENT.pipeline().getVertexFormatBinding(0);
        int baseVertex = (int)(slice.vertexBufferOffset() / format.getVertexSize());
        int finalUbo = uboIndex;
        List<RenderPass.Draw<GpuBufferSlice[]>> draws =
                drawGroups.get(ChunkSectionLayer.TRANSLUCENT).computeIfAbsent(173, unused -> new ArrayList<>());
        draws.add(new RenderPass.Draw<>(
                0,
                slice.vertexBuffer(),
                indexBuffer,
                indexType,
                firstIndex,
                draw.indexCount(),
                baseVertex,
                (ubos, uploader) -> uploader.upload("ChunkSection", ubos[finalUbo])));
        return largest;
    }

    private RetainedSectionRecord recordFor(final SectionRenderDispatcher.RenderSection section) {
        int slot = section.index;
        if (slot >= this.sections.length) {
            int newSize = Math.max(slot + 1, this.sections.length == 0 ? 256 : this.sections.length * 2);
            RetainedSectionRecord[] grown = new RetainedSectionRecord[newSize];
            System.arraycopy(this.sections, 0, grown, 0, this.sections.length);
            this.sections = grown;
        }
        RetainedSectionRecord record = this.sections[slot];
        if (record == null) {
            record = new RetainedSectionRecord();
            this.sections[slot] = record;
            record.resetIdentity(
                    slot,
                    section.getSectionNode(),
                    section.getRenderOrigin().getX(),
                    section.getRenderOrigin().getY(),
                    section.getRenderOrigin().getZ());
        } else if (record.sectionNode != section.getSectionNode()) {
            record.resetIdentity(
                    slot,
                    section.getSectionNode(),
                    section.getRenderOrigin().getX(),
                    section.getRenderOrigin().getY(),
                    section.getRenderOrigin().getZ());
            this.frameCommandRebuilds++;
            this.reuseFailed = true;
        }
        return record;
    }

    private OpaqueDrawBatch allocateBatch() {
        if (!this.batchPool.isEmpty()) {
            return this.batchPool.remove(this.batchPool.size() - 1);
        }
        return new OpaqueDrawBatch(RetainedTerrainPipelines.BATCH_SIZE);
    }

    private void recycleBatches() {
        this.batchPool.addAll(this.batches);
        this.batches.clear();
    }

    private int totalOpaqueDraws() {
        int total = 0;
        for (OpaqueDrawBatch batch : this.batches) {
            total += batch.count;
        }
        return total;
    }
}
