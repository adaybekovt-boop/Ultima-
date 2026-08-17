package dev.ultima.client.fsr;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultima-owned FSR1 EASU/RCAS pipelines. Vanilla blit pipelines stay registered.
 */
public final class FsrPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-fsr");
    public static final Identifier SCREENQUAD = Identifier.fromNamespaceAndPath("ultima", "core/fsr_screenquad");
    public static final Identifier EASU = Identifier.fromNamespaceAndPath("ultima", "core/fsr_easu");
    public static final Identifier RCAS = Identifier.fromNamespaceAndPath("ultima", "core/fsr_rcas");

    public static final BindGroupLayout FSR_CONSTANTS = BindGroupLayout.builder()
            .withUniform("FsrConstants", UniformType.UNIFORM_BUFFER)
            .build();

    private static @Nullable RenderPipeline easu;
    private static @Nullable RenderPipeline rcas;
    private static boolean compileFailed;
    private static String compileBackend = "";

    private FsrPipelines() {
    }

    public static void invalidate() {
        easu = null;
        rcas = null;
        compileFailed = false;
        compileBackend = "";
    }

    public static boolean compileFailed() {
        return compileFailed;
    }

    public static String compileBackend() {
        return compileBackend;
    }

    public static boolean ensureCompiled() {
        if (compileFailed) {
            return false;
        }
        if (easu != null && rcas != null) {
            return true;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        ShaderManager shaders = minecraft.getShaderManager();
        if (shaders.getShader(SCREENQUAD, ShaderType.VERTEX) == null
                || shaders.getShader(EASU, ShaderType.FRAGMENT) == null
                || shaders.getShader(RCAS, ShaderType.FRAGMENT) == null) {
            return false;
        }
        try {
            compileBackend = device.getDeviceInfo().backendName();
            RenderPipeline easuPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("ultima", "pipeline/fsr_easu"))
                    .withVertexShader(SCREENQUAD)
                    .withFragmentShader(EASU)
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withBindGroupLayout(FSR_CONSTANTS)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build();
            RenderPipeline rcasPipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("ultima", "pipeline/fsr_rcas"))
                    .withVertexShader(SCREENQUAD)
                    .withFragmentShader(RCAS)
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withBindGroupLayout(FSR_CONSTANTS)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .build();
            CompiledRenderPipeline easuCompiled = device.precompilePipeline(easuPipeline);
            CompiledRenderPipeline rcasCompiled = device.precompilePipeline(rcasPipeline);
            if (!easuCompiled.isValid() || !rcasCompiled.isValid()) {
                LOGGER.warn("FSR1 pipelines failed to compile on {}; FSR will fail open.", compileBackend);
                compileFailed = true;
                return false;
            }
            easu = easuPipeline;
            rcas = rcasPipeline;
            LOGGER.info("FSR1 EASU+RCAS pipelines compiled on {}.", compileBackend);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("FSR1 pipeline compilation threw; FSR will fail open.", e);
            compileFailed = true;
            return false;
        }
    }

    public static RenderPipeline easu() {
        return easu;
    }

    public static RenderPipeline rcas() {
        return rcas;
    }
}
