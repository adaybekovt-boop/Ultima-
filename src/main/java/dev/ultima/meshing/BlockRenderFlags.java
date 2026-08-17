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
    /**
     * Neighbor {@code getFaceOcclusionShape} is the {@code Shapes.block()} singleton
     * on every face. Test-fixture / debug bit only. Production compile never
     * reads this; it calls {@code Block.shouldRenderFace}.
     */
    public static final int FULL_BLOCK_OCCLUDER = 1 << 5;
    /**
     * Occlusion shape is empty (air, glass, plants). Test-fixture / debug bit
     * only. Production compile never reads this.
     */
    public static final int EMPTY_OCCLUDER = 1 << 6;
    /**
     * {@code skipRendering(self, self)} is true (leaves). Fast path refuses the cell.
     * Test-fixture / debug bit only; production snapshots do not compute it.
     */
    public static final int SKIP_RENDERING = 1 << 7;
    /**
     * Translucent render layer (glass, ice, slime). Always vanilla fallback.
     * Fixture bit; production rejects via {@code ChunkSectionLayer.TRANSLUCENT}.
     */
    public static final int TRANSLUCENT = 1 << 8;

    private BlockRenderFlags() {
    }

    public static int pack(
            final boolean air,
            final boolean solidRender,
            final boolean hasBlockEntity,
            final boolean hasFluid,
            final boolean model,
            final boolean fullBlockOccluder,
            final boolean emptyOccluder,
            final boolean skipRendering) {
        return pack(air, solidRender, hasBlockEntity, hasFluid, model, fullBlockOccluder, emptyOccluder, skipRendering, false);
    }

    public static int pack(
            final boolean air,
            final boolean solidRender,
            final boolean hasBlockEntity,
            final boolean hasFluid,
            final boolean model,
            final boolean fullBlockOccluder,
            final boolean emptyOccluder,
            final boolean skipRendering,
            final boolean translucent) {
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
        if (fullBlockOccluder) {
            flags |= FULL_BLOCK_OCCLUDER;
        }
        if (emptyOccluder) {
            flags |= EMPTY_OCCLUDER;
        }
        if (skipRendering) {
            flags |= SKIP_RENDERING;
        }
        if (translucent) {
            flags |= TRANSLUCENT;
        }
        return flags;
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

    public static boolean fullBlockOccluder(final int flags) {
        return (flags & FULL_BLOCK_OCCLUDER) != 0;
    }

    public static boolean emptyOccluder(final int flags) {
        return (flags & EMPTY_OCCLUDER) != 0;
    }

    public static boolean skipRendering(final int flags) {
        return (flags & SKIP_RENDERING) != 0;
    }

    public static boolean translucent(final int flags) {
        return (flags & TRANSLUCENT) != 0;
    }
}
