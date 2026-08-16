package dev.ultima.review;

import dev.ultima.util.BitSetRuns;
import dev.ultima.util.IndirectCommandPacking;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * Source-level and optional shaderc checks for the retained foundation.
 * Full Minecraft pipeline compile still happens at runtime via
 * {@code device.precompilePipeline}.
 */
final class RetainedFoundationChecks {
    private static final Path SHADER_ROOT = Path.of("src/client/resources/assets/ultima/shaders");

    private RetainedFoundationChecks() {
    }

    static void run() {
        testShaderSourcesBanDrawIdArb();
        testSectionTableUsesBaseInstance();
        testDirtyRangeCoalescing();
        testIndirectFirstInstanceIsSectionSlot();
        testShadercPortabilitySnippets();
    }

    private static void testShaderSourcesBanDrawIdArb() {
        for (String relative : List.of(
                "core/terrain_retained.vsh",
                "core/terrain_retained.fsh",
                "include/section_table.glsl")) {
            String source = readShader(relative);
            if (source.contains("gl_DrawIDARB") || source.contains("gl_DrawID")) {
                throw new AssertionError(relative + " must not use gl_DrawID / gl_DrawIDARB");
            }
        }
        String include = readShader("include/section_table.glsl");
        if (include.contains("#version")) {
            throw new AssertionError("section_table.glsl is imported and must not declare #version");
        }
        String vertex = readShader("core/terrain_retained.vsh");
        if (!vertex.contains("ULTIMA_GL_DRAW_PARAMETERS")) {
            throw new AssertionError("OpenGL retained vertex shader must gate the ARB extension");
        }
        if (!vertex.contains("#moj_import <ultima:section_table.glsl>")) {
            throw new AssertionError("retained vertex shader must import the section table");
        }
    }

    private static void testSectionTableUsesBaseInstance() {
        String include = readShader("include/section_table.glsl");
        if (!include.contains("gl_BaseInstanceARB") || !include.contains("gl_BaseInstance")) {
            throw new AssertionError("section table must index via gl_BaseInstance / gl_BaseInstanceARB");
        }
        if (!include.contains("uniform isamplerBuffer UltimaSectionTable")) {
            throw new AssertionError("section table must be a persistent isamplerBuffer");
        }
        if (!include.contains("texelFetch(UltimaSectionTable")) {
            throw new AssertionError("section table must be fetched with texelFetch");
        }
    }

    private static void testDirtyRangeCoalescing() {
        BitSet dirty = new BitSet();
        dirty.set(0);
        dirty.set(1);
        dirty.set(2);
        dirty.set(8);
        dirty.set(10);
        List<String> runs = new ArrayList<>();
        int count = BitSetRuns.forEachRun(dirty, 16, (from, to) -> runs.add(from + "-" + to));
        if (count != 3 || !List.of("0-3", "8-9", "10-11").equals(runs)) {
            throw new AssertionError("dirty runs must coalesce adjacent bits: " + runs);
        }
        BitSet empty = new BitSet();
        if (BitSetRuns.forEachRun(empty, 8, (from, to) -> {
            throw new AssertionError("empty dirty set must emit no runs");
        }) != 0) {
            throw new AssertionError("empty dirty set run count");
        }
    }

    private static void testIndirectFirstInstanceIsSectionSlot() {
        ByteBuffer commands = ByteBuffer.allocate(IndirectCommandPacking.STRIDE * 2).order(ByteOrder.nativeOrder());
        IndirectCommandPacking.write(commands, 36, 1, 12, 4, 17);
        IndirectCommandPacking.write(commands, 6, 0, 0, 0, 99);
        commands.flip();
        if (IndirectCommandPacking.firstInstance(commands, 0) != 17) {
            throw new AssertionError("firstInstance must be the section-table slot");
        }
        if (IndirectCommandPacking.firstInstance(commands, 1) != 99) {
            throw new AssertionError("hidden command must keep its section slot");
        }
        if (commands.getInt(4) != 1 || commands.getInt(IndirectCommandPacking.STRIDE + 4) != 0) {
            throw new AssertionError("instanceCount 0/1 is the visibility bit");
        }
    }

    private static void testShadercPortabilitySnippets() {
        String vulkan = """
                #version 450
                uniform isamplerBuffer UltimaSectionTable;
                void main() {
                    int slot = gl_BaseInstance;
                    gl_Position = vec4(texelFetch(UltimaSectionTable, slot));
                }
                """;
        String bannedVulkanDrawId = """
                #version 450
                #extension GL_ARB_shader_draw_parameters : enable
                void main() {
                    gl_Position = vec4(gl_DrawIDARB);
                }
                """;
        String bannedVulkanBaseInstanceArb = """
                #version 450
                #extension GL_ARB_shader_draw_parameters : enable
                void main() {
                    gl_Position = vec4(gl_BaseInstanceARB);
                }
                """;
        try {
            compileShaderc(vulkan, 0, 4202496, "ultima-vulkan-baseinstance.vert");
            expectShadercFailure(bannedVulkanDrawId, "gl_DrawIDARB");
            expectShadercFailure(bannedVulkanBaseInstanceArb, "gl_BaseInstanceARB");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError missingNatives) {
            System.out.println("shaderc natives unavailable; source-level portability checks still passed.");
        }
    }

    private static void expectShadercFailure(final String source, final String token) {
        try {
            compileShaderc(source, 0, 4202496, "ultima-vulkan-banned-" + token + ".vert");
            throw new AssertionError("Vulkan shaderc must reject " + token);
        } catch (ShaderCompileFailure expected) {
            if (!expected.getMessage().contains(token) && !expected.getMessage().contains(token.substring(3))) {
                throw new AssertionError("expected " + token + " compile error, got: " + expected.getMessage());
            }
        }
    }

    private static void compileShaderc(final String source, final int targetEnv, final int version, final String filename) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new UnsatisfiedLinkError("shaderc compiler handle is null");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
        ByteBuffer filenameBuffer = MemoryUtil.memUTF8(filename);
        ByteBuffer entry = MemoryUtil.memUTF8("main");
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, targetEnv, version);
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, 0, filenameBuffer, entry, options);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != 0) {
                throw new ShaderCompileFailure(Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            MemoryUtil.memFree(entry);
            MemoryUtil.memFree(filenameBuffer);
            MemoryUtil.memFree(sourceBuffer);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static String readShader(final String relative) {
        Path path = SHADER_ROOT.resolve(relative);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("missing shader " + path.toAbsolutePath(), e);
        }
    }

    private static final class ShaderCompileFailure extends RuntimeException {
        private ShaderCompileFailure(final String message) {
            super(message);
        }
    }
}
