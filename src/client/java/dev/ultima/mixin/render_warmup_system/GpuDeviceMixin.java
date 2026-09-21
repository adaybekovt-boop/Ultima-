package dev.ultima.mixin.render_warmup_system;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.ultima.client.warmup.FirstUseProfiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GpuDevice.class)
public abstract class GpuDeviceMixin {
    @WrapMethod(method = "precompilePipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/shaders/ShaderSource;)Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;")
    private CompiledRenderPipeline ultima$profilePipelineCompilation(
            final RenderPipeline pipeline,
            final @Nullable ShaderSource shaderSource,
            final Operation<CompiledRenderPipeline> original) {
        FirstUseProfiler.Token token = FirstUseProfiler.begin(
                "render_pipeline", pipeline.getLocation().toString());
        try {
            return original.call(pipeline, shaderSource);
        } finally {
            FirstUseProfiler.end(token);
        }
    }
}
