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
 * Opaque retained producer. Mesh and indirect commands stay on the GPU;
 * this class only dirties section-table slots, visibility bits, and group membership.
 * Translucent terrain is still built as vanilla draws.
 */
public final class RetainedTerrainRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-retained-terrain");
    private static final RetainedTerrainRenderer INSTANCE = new RetainedTerrainRenderer();

    private final RetainedGpuResources gpu = new RetainedGpuResources();
    private RetainedSectionRecord[] sections = new RetainedSectionRecord[0];
    private final List<SubmitGroup> groups = new ArrayList<>();
    private final EnumMap<ChunkSectionLayer, ArrayList<SubmitGroup>> groupsByLayer = new EnumMap<>(ChunkSectionLayer.class);
    private ArrayList<RetainedSectionRecord.LayerSlot> previouslyVisible = new ArrayList<>();
    private ArrayList<RetainedSectionRecord.LayerSlot> currentlyVisible = new ArrayList<>();
    private @Nullable GpuTextureView blockAtlas;
    private final Matrix4f headerModelView = new Matrix4f();
    private boolean opaqueReady;
    private boolean failedOpen;
    private int frameVisibleSections;
    private int frameSectionLayers;
    private int frameCommandRebuilds;
    private int frameMetadataUpdates;
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
        this.closeGroups();
        this.gpu.close();
        this.sections = new RetainedSectionRecord[0];
        this.previouslyVisible.clear();
        this.currentlyVisible.clear();
        this.opaqueReady = false;
        this.failedOpen = false;
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
            this.blockAtlas = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            this.headerModelView.set(modelView);
            int atlasW = this.blockAtlas.getWidth(0);
            int atlasH = this.blockAtlas.getHeight(0);
            this.frameVisibleSections = visibleSections.size();
            this.frameSectionLayers = 0;
            this.frameCommandRebuilds = 0;
            this.frameMetadataUpdates = 0;
            this.frameOpaqueDraws = 0;
            this.currentlyVisible.clear();

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
                this.gpu.ensureSectionSlots(Math.max(1, this.sections.length));
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

            this.hideUnseenSlots();
            this.pruneDeadGroups();
            this.gpu.markHeader(this.headerModelView, atlasW, atlasH);

            GpuBufferSlice[] translucentUbos = RenderSystem.getDynamicUniforms()
                    .writeChunkSections(translucentInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
            this.opaqueReady = true;
            boolean commandsPersisted = this.frameCommandRebuilds == 0 && !this.groups.isEmpty();
            TerrainFrameMetrics.setRetainedActive(true, RetainedTerrainCapabilities.describeSubmitMode());
            TerrainFrameMetrics.setCommandBatchesReused(commandsPersisted);
            TerrainFrameMetrics.recordVisible(
                    this.frameVisibleSections,
                    this.frameSectionLayers,
                    this.frameOpaqueDraws,
                    2 + translucentInfos.size());
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
            OpaqueTerrainSubmitter.submit(this.groups, this.gpu, this.blockAtlas, sampler);
            TerrainFrameMetrics.recordRetainedUploads(this.gpu.metrics);
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
            this.hideSlot(record.solid);
            this.hideSlot(record.cutout);
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
            this.gpu.markSection(record.slot, record.originX, record.originY, record.originZ, record.visibility);
            this.frameMetadataUpdates++;
        }
        record.meshId = meshId;
        record.visibleThisFrame = true;
        record.temporalFlags = RetainedSectionRecord.FLAG_STATIC_WORLD_TRANSFORM;
        this.captureLayer(record, dispatcher, mesh, ChunkSectionLayer.SOLID, meshId);
        this.captureLayer(record, dispatcher, mesh, ChunkSectionLayer.CUTOUT, meshId);
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
            this.hideSlot(slot);
            return;
        }
        VertexFormat format = layer.pipeline().getVertexFormatBinding(0);
        boolean meshChanged = slot.capture(draw, slice, format, meshId);
        if (!slot.alive || slot.vertexBuffer == null) {
            this.hideSlot(slot);
            return;
        }
        if (meshChanged || slot.group == null) {
            SubmitGroup group = this.findOrCreateGroup(layer, slot);
            if (slot.group != null && slot.group != group) {
                slot.group.remove(slot);
            }
            if (slot.group == group) {
                group.updateImmutable(slot);
            } else {
                group.add(slot, record.slot);
            }
            this.frameCommandRebuilds++;
        } else if (slot.instanceCount == 0) {
            slot.group.setVisible(slot, true);
        }
        slot.seenThisFrame = true;
        this.currentlyVisible.add(slot);
        this.frameSectionLayers++;
        this.frameOpaqueDraws++;
    }

    private void hideSlot(final RetainedSectionRecord.LayerSlot slot) {
        if (slot.group != null && slot.instanceCount != 0) {
            slot.group.setVisible(slot, false);
        }
    }

    private void hideUnseenSlots() {
        for (int i = 0; i < this.previouslyVisible.size(); i++) {
            RetainedSectionRecord.LayerSlot slot = this.previouslyVisible.get(i);
            if (!slot.seenThisFrame) {
                this.hideSlot(slot);
            }
            slot.seenThisFrame = false;
        }
        for (int i = 0; i < this.currentlyVisible.size(); i++) {
            this.currentlyVisible.get(i).seenThisFrame = false;
        }
        ArrayList<RetainedSectionRecord.LayerSlot> swap = this.previouslyVisible;
        this.previouslyVisible = this.currentlyVisible;
        this.currentlyVisible = swap;
        this.currentlyVisible.clear();
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
            this.gpu.ensureSectionSlots(newSize);
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
            this.gpu.markSection(slot, record.originX, record.originY, record.originZ, record.visibility);
            this.frameMetadataUpdates++;
        } else if (record.sectionNode != section.getSectionNode()) {
            record.resetIdentity(
                    slot,
                    section.getSectionNode(),
                    section.getRenderOrigin().getX(),
                    section.getRenderOrigin().getY(),
                    section.getRenderOrigin().getZ());
            this.gpu.markSection(slot, record.originX, record.originY, record.originZ, record.visibility);
            this.frameCommandRebuilds++;
            this.frameMetadataUpdates++;
        }
        return record;
    }

    private SubmitGroup findOrCreateGroup(final ChunkSectionLayer layer, final RetainedSectionRecord.LayerSlot slot) {
        ArrayList<SubmitGroup> layerGroups = this.groupsByLayer.computeIfAbsent(layer, unused -> new ArrayList<>());
        for (int i = 0; i < layerGroups.size(); i++) {
            SubmitGroup group = layerGroups.get(i);
            if (group.matches(layer, slot.vertexBuffer, slot.indexBuffer, slot.indexType)) {
                return group;
            }
        }
        SubmitGroup created = new SubmitGroup(layer, slot.vertexBuffer, slot.indexBuffer, slot.indexType);
        layerGroups.add(created);
        this.groups.add(created);
        return created;
    }

    private void pruneDeadGroups() {
        for (int i = this.groups.size() - 1; i >= 0; i--) {
            SubmitGroup group = this.groups.get(i);
            boolean closed = group.vertexBuffer.isClosed()
                    || (group.indexBuffer != null && group.indexBuffer.isClosed());
            if (!closed && group.count > 0) {
                continue;
            }
            group.close();
            this.groups.remove(i);
            ArrayList<SubmitGroup> layerGroups = this.groupsByLayer.get(group.layer);
            if (layerGroups != null) {
                layerGroups.remove(group);
            }
        }
    }

    private void closeGroups() {
        for (int i = 0; i < this.groups.size(); i++) {
            this.groups.get(i).close();
        }
        this.groups.clear();
        this.groupsByLayer.clear();
    }
}
