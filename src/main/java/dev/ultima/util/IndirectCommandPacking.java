package dev.ultima.util;

import java.nio.ByteBuffer;

/**
 * CPU-side layout of {@code VkDrawIndexedIndirectCommand} / OpenGL
 * {@code DrawElementsIndirectCommand}. {@code firstInstance} is the section-table
 * slot consumed as {@code gl_BaseInstanceARB} in the retained shader.
 * Unsuffixed {@code gl_BaseInstance} is undeclared in Minecraft 26.2's Vulkan shaderc.
 */
public final class IndirectCommandPacking {
    public static final int STRIDE = 20;

    private IndirectCommandPacking() {
    }

    public static void write(
            final ByteBuffer dest,
            final int indexCount,
            final int instanceCount,
            final int firstIndex,
            final int baseVertex,
            final int firstInstance) {
        dest.putInt(indexCount);
        dest.putInt(instanceCount);
        dest.putInt(firstIndex);
        dest.putInt(baseVertex);
        dest.putInt(firstInstance);
    }

    public static int firstInstance(final ByteBuffer commands, final int commandIndex) {
        return commands.getInt(commandIndex * STRIDE + 16);
    }
}
