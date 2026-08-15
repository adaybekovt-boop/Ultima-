package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

final class OpaqueTerrainSubmitter {
    private OpaqueTerrainSubmitter() {
    }

    static void submit(
            final List<OpaqueDrawBatch> batches,
            final GpuBufferSlice header,
            final RetainedGpuResources gpu,
            final GpuTextureView blockAtlas,
            final GpuSampler sampler) {
        RenderTarget renderTarget = ChunkSectionLayerGroup.OPAQUE.outputTarget();
        Minecraft minecraft = Minecraft.getInstance();
        DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
        String mode = RetainedTerrainCapabilities.describeSubmitMode();

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Ultima retained opaque terrain",
                        renderTarget.getColorTextureView(),
                        Optional.empty(),
                        renderTarget.getDepthTextureView(),
                        OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler0", blockAtlas, sampler);
            renderPass.bindTexture(
                    "Sampler2",
                    minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            renderPass.setUniform("ChunkSection", header);

            drawLayer(renderPass, gpu, batches, ChunkSectionLayer.SOLID, features, mode);
            drawLayer(renderPass, gpu, batches, ChunkSectionLayer.CUTOUT, features, mode);
        }
    }

    private static void drawLayer(
            final RenderPass renderPass,
            final RetainedGpuResources gpu,
            final List<OpaqueDrawBatch> batches,
            final ChunkSectionLayer layer,
            final DeviceFeatures features,
            final String mode) {
        RenderPipeline pipeline = layer == ChunkSectionLayer.CUTOUT
                ? RetainedTerrainPipelines.cutout()
                : RetainedTerrainPipelines.solid();
        boolean pipelineSet = false;
        for (OpaqueDrawBatch batch : batches) {
            if (batch.count <= 0 || batch.layer != layer) {
                continue;
            }
            if (!pipelineSet) {
                renderPass.setPipeline(pipeline);
                pipelineSet = true;
            }
            GpuBufferSlice table = gpu.writeBatch(batch);
            renderPass.setUniform("UltimaSectionBatch", table);
            renderPass.setVertexBuffer(0, batch.vertexBuffer.slice());
            IndexType indexType = batch.indexType;
            GpuBuffer indexBuffer = batch.indexBuffer;
            if (indexBuffer == null) {
                RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                indexBuffer = sequential.getBuffer(batch.maxIndexCount);
                indexType = sequential.type();
            }
            renderPass.setIndexBuffer(indexBuffer, indexType);
            drawBatch(renderPass, gpu, batch, indexType, features, mode);
        }
    }

    private static void drawBatch(
            final RenderPass renderPass,
            final RetainedGpuResources gpu,
            final OpaqueDrawBatch batch,
            final IndexType indexType,
            final DeviceFeatures features,
            final String mode) {
        if (("indirect".equals(mode) || "indirect_single".equals(mode)) && features.drawIndirect()) {
            GpuBufferSlice commands = gpu.writeIndirect(batch);
            if (batch.count == 1 || features.multiDrawIndirect()) {
                renderPass.drawIndexedIndirect(commands, batch.count);
                return;
            }
            for (int i = 0; i < batch.count; i++) {
                renderPass.drawIndexedIndirect(
                        commands.slice((long)i * RetainedGpuResources.INDIRECT_STRIDE, RetainedGpuResources.INDIRECT_STRIDE),
                        1);
            }
            return;
        }
        if (features.multiDrawDirectSeparate()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer offsets = stack.mallocPointer(batch.count);
                IntBuffer counts = stack.mallocInt(batch.count);
                IntBuffer bases = stack.mallocInt(batch.count);
                for (int i = 0; i < batch.count; i++) {
                    offsets.put(i, (long)batch.firstIndex[i] * indexType.bytes);
                    counts.put(i, batch.indexCount[i]);
                    bases.put(i, batch.baseVertex[i]);
                }
                offsets.position(0).limit(batch.count);
                counts.position(0).limit(batch.count);
                bases.position(0).limit(batch.count);
                renderPass.multiDrawIndexed(offsets, counts, bases, batch.count);
            }
            return;
        }
        if (features.multiDrawDirectInterleaved()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer parameters = stack.mallocInt(batch.count * 3);
                for (int i = 0; i < batch.count; i++) {
                    parameters.put(batch.firstIndex[i]);
                    parameters.put(batch.indexCount[i]);
                    parameters.put(batch.baseVertex[i]);
                }
                parameters.flip();
                renderPass.multiDrawIndexed(parameters, 1, 0, batch.count);
            }
            return;
        }
        for (int i = 0; i < batch.count; i++) {
            renderPass.drawIndexed(batch.indexCount[i], 1, batch.firstIndex[i], batch.baseVertex[i], i);
        }
    }
}
