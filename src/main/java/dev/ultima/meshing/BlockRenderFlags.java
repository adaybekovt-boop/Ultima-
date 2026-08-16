package dev.ultima.meshing;

/**
 * Compact per-cell render flags. Bits are properties vanilla {@code SectionCompiler}
 * queries before tessellation; they do not replace model lookup.
 */
public final class BlockRenderFlags {
    public static final int AIR = 1;
    public static final int SOLID_RENDER = 1 << 1;
    public static final int HAS_BLOCK_ENTITY = 1 << 2;
    public static final int HAS_FLUID = 1 << 3;
    public static final int MODEL = 1 << 4;

    private BlockRenderFlags() {
    }

    public static byte pack(
            final boolean air,
            final boolean solidRender,
            final boolean hasBlockEntity,
            final boolean hasFluid,
            final boolean model) {
        int flags = 0;
        if (air) {
            flags |= AIR;
        }
        if (solidRender) {
            flags |= SOLID_RENDER;
        }
        if (hasBlockEntity) {
            flags |= HAS_BLOCK_ENTITY;
        }
        if (hasFluid) {
            flags |= HAS_FLUID;
        }
        if (model) {
            flags |= MODEL;
        }
        return (byte)flags;
    }

    public static boolean air(final int flags) {
        return (flags & AIR) != 0;
    }

    public static boolean solidRender(final int flags) {
        return (flags & SOLID_RENDER) != 0;
    }

    public static boolean hasBlockEntity(final int flags) {
        return (flags & HAS_BLOCK_ENTITY) != 0;
    }

    public static boolean hasFluid(final int flags) {
        return (flags & HAS_FLUID) != 0;
    }

    public static boolean model(final int flags) {
        return (flags & MODEL) != 0;
    }
}
