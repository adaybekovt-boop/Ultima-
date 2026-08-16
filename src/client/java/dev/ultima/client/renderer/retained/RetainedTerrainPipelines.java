package dev.ultima.client.renderer.retained;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultima-owned opaque terrain pipelines. Vanilla SOLID/CUTOUT pipelines stay
 * registered for fallback and for other mods that look them up by location.
 */
public final class RetainedTerrainPipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-retained-terrain");
    public static final Identifier SHADER = Identifier.fromNamespaceAndPath("ultima", "core/terrain_retained");

    public static final BindGroupLayout SECTION_TABLE = BindGroupLayout.builder()
            .withUniform("UltimaSectionTable", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_SINT)
            .build();

    private static @Nullable RenderPipeline solid;
    private static @Nullable RenderPipeline cutout;
    private static boolean compileFailed;
    private static String compileBackend = "";

    private RetainedTerrainPipelines() {
    }

    public static void invalidate() {
        solid = null;
        cutout = null;
        compileFailed = false;
        compileBackend = "";
    }

    public static boolean compileFailed() {
        return compileFailed;
    }

    public static String compileBackend() {
        return compileBackend;
    }

    public static boolean isOpenGlBackend(final GpuDevice device) {
        String name = device.getDeviceInfo().backendName();
        return name != null && name.toLowerCase().contains("opengl");
    }

    public static boolean ensureCompiled() {
        if (compileFailed) {
            return false;
        }
        if (solid != null && cutout != null) {
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
        if (shaders.getShader(SHADER, ShaderType.VERTEX) == null
                || shaders.getShader(SHADER, ShaderType.FRAGMENT) == null) {
            return false;
        }
        try {
            boolean opengl = isOpenGlBackend(device);
            compileBackend = device.getDeviceInfo().backendName();
            RenderPipeline.Builder solidBuilder = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("ultima", "pipeline/solid_terrain_retained"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(SHADER)
                    .withBindGroupLayout(SECTION_TABLE);
            RenderPipeline.Builder cutoutBuilder = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("ultima", "pipeline/cutout_terrain_retained"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(SHADER)
                    .withShaderDefine("ALPHA_CUTOUT", 0.5F)
                    .withBindGroupLayout(SECTION_TABLE);
            if (opengl) {
                solidBuilder.withShaderDefine("ULTIMA_GL_DRAW_PARAMETERS");
                cutoutBuilder.withShaderDefine("ULTIMA_GL_DRAW_PARAMETERS");
            }
            RenderPipeline solidPipeline = solidBuilder.build();
            RenderPipeline cutoutPipeline = cutoutBuilder.build();
            CompiledRenderPipeline solidCompiled = device.precompilePipeline(solidPipeline);
            CompiledRenderPipeline cutoutCompiled = device.precompilePipeline(cutoutPipeline);
            if (!solidCompiled.isValid() || !cutoutCompiled.isValid()) {
                LOGGER.warn(
                        "Retained terrain pipelines failed to compile on {}; falling back to vanilla.",
                        compileBackend);
                compileFailed = true;
                return false;
            }
            solid = solidPipeline;
            cutout = cutoutPipeline;
            LOGGER.info(
                    "Retained opaque terrain pipelines compiled on {} (gl_BaseInstanceARB, texel section table).",
                    compileBackend);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Retained terrain pipeline compilation threw; falling back to vanilla.", e);
            compileFailed = true;
            return false;
        }
    }

    public static RenderPipeline solid() {
        return solid;
    }

    public static RenderPipeline cutout() {
        return cutout;
    }
}
