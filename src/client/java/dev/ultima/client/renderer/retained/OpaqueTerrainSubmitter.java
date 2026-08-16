package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;

/**
 * One encoder, dirty {@code writeToBuffer} uploads, then the same single opaque
 * render pass vanilla already uses. Header + section table are bound once.
 */
final class OpaqueTerrainSubmitter {
    private OpaqueTerrainSubmitter() {
    }

    static void submit(
            final List<SubmitGroup> groups,
            final RetainedGpuResources gpu,
            final GpuTextureView blockAtlas,
            final GpuSampler sampler) {
        RenderTarget renderTarget = ChunkSectionLayerGroup.OPAQUE.outputTarget();
        Minecraft minecraft = Minecraft.getInstance();
        DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
        String mode = RetainedTerrainCapabilities.describeSubmitMode();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        gpu.metrics.encoders++;
        gpu.flush(encoder);
        for (int i = 0; i < groups.size(); i++) {
            groups.get(i).flushCommands(encoder, gpu.metrics);
        }

        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "Section layers for " + ChunkSectionLayerGroup.OPAQUE.label(),
                renderTarget.getColorTextureView(),
                Optional.empty(),
                renderTarget.getDepthTextureView(),
                OptionalDouble.empty())) {
            gpu.metrics.renderPasses++;
            gpu.timers.beginPass(renderPass);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler0", blockAtlas, sampler);
            renderPass.bindTexture(
                    "Sampler2",
                    minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            renderPass.setUniform("ChunkSection", gpu.headerBuffer());
            renderPass.setUniform("UltimaSectionTable", gpu.sectionTableBuffer());

            drawLayer(renderPass, groups, ChunkSectionLayer.SOLID, features, mode);
            drawLayer(renderPass, groups, ChunkSectionLayer.CUTOUT, features, mode);
            gpu.timers.endPass(renderPass);
        }
    }

    private static void drawLayer(
            final RenderPass renderPass,
            final List<SubmitGroup> groups,
            final ChunkSectionLayer layer,
            final DeviceFeatures features,
            final String mode) {
        RenderPipeline pipeline = layer == ChunkSectionLayer.CUTOUT
                ? RetainedTerrainPipelines.cutout()
                : RetainedTerrainPipelines.solid();
        boolean pipelineSet = false;
        for (int i = 0; i < groups.size(); i++) {
            SubmitGroup group = groups.get(i);
            if (group.layer != layer || !group.hasLiveDraws() || group.vertexBuffer.isClosed()) {
                continue;
            }
            if (!pipelineSet) {
                renderPass.setPipeline(pipeline);
                pipelineSet = true;
            }
            renderPass.setVertexBuffer(0, group.vertexBuffer.slice());
            IndexType indexType = group.indexType;
            GpuBuffer indexBuffer = group.indexBuffer;
            if (indexBuffer == null) {
                RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                indexBuffer = sequential.getBuffer(group.maxIndexCount);
                indexType = sequential.type();
            }
            renderPass.setIndexBuffer(indexBuffer, indexType);
            drawGroup(renderPass, group, features, mode);
        }
    }

    private static void drawGroup(
            final RenderPass renderPass,
            final SubmitGroup group,
            final DeviceFeatures features,
            final String mode) {
        if (("indirect".equals(mode) || "indirect_single".equals(mode)) && features.drawIndirect()) {
            GpuBufferSlice commands = group.commandSlice();
            if (commands != null) {
                if (group.count == 1 || features.multiDrawIndirect()) {
                    renderPass.drawIndexedIndirect(commands, group.count);
                    return;
                }
                for (int i = 0; i < group.count; i++) {
                    GpuBufferSlice one = group.commandSlice(i);
                    if (one != null) {
                        renderPass.drawIndexedIndirect(one, 1);
                    }
                }
                return;
            }
        }
        for (int i = 0; i < group.count; i++) {
            if (group.instanceCount[i] <= 0) {
                continue;
            }
            renderPass.drawIndexed(
                    group.indexCount[i],
                    group.instanceCount[i],
                    group.firstIndex[i],
                    group.baseVertex[i],
                    group.sectionSlot[i]);
        }
    }
}
