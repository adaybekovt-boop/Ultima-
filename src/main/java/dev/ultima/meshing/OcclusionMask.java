package dev.ultima.meshing;

import net.minecraft.core.Direction;

/**
 * Six-bit visible-face mask. Bit {@code 1 << Direction.ordinal()} is set when
 * vanilla {@code Block.shouldRenderFace} would emit that face for a simple
 * full-cube / empty-occluder pair.
 *
 * <p>Production uses {@code Block.shouldRenderFace} on real {@code BlockState}s.
 * This helper is the same first-two-branches identity for tests: a
 * {@code Shapes.block()} neighbor always culls; an empty occluder always
 * renders when {@code skipRendering} is false. Any other neighbor is
 * {@link #COMPLEX} and forces fallback for that cell in the kernel.
 */
public final class OcclusionMask {
    public static final int COMPLEX = -1;
    private static final Direction[] DIRECTIONS = Direction.values();

    private OcclusionMask() {
    }

    public static int visibleFaces(
            final int selfFlags,
            final int neighborNegX,
            final int neighborPosX,
            final int neighborNegY,
            final int neighborPosY,
            final int neighborNegZ,
            final int neighborPosZ) {
        if (BlockRenderFlags.skipRendering(selfFlags)) {
            return COMPLEX;
        }
        int mask = 0;
        // Direction.values() is DOWN, UP, NORTH, SOUTH, WEST, EAST — not the packed neighbor order.
        int[] byDirection = {
                neighborNegY, neighborPosY, neighborNegZ, neighborPosZ, neighborNegX, neighborPosX
        };
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int neighbor = byDirection[i];
            int face = simpleShouldRenderFace(selfFlags, neighbor);
            if (face < 0) {
                return COMPLEX;
            }
            if (face == 1) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    public static int visibleFaces(final PackedSectionVolume volume, final int x, final int y, final int z) {
        return visibleFaces(
                volume.flags(SectionIndex.interior(x, y, z)),
                volume.neighborFlags(x, y, z, -1, 0, 0),
                volume.neighborFlags(x, y, z, 1, 0, 0),
                volume.neighborFlags(x, y, z, 0, -1, 0),
                volume.neighborFlags(x, y, z, 0, 1, 0),
                volume.neighborFlags(x, y, z, 0, 0, -1),
                volume.neighborFlags(x, y, z, 0, 0, 1));
    }

    /**
     * @return 1 emit, 0 cull, -1 not a simple pair
     */
    public static int simpleShouldRenderFace(final int selfFlags, final int neighborFlags) {
        if (BlockRenderFlags.fullBlockOccluder(neighborFlags)) {
            return 0;
        }
        if (BlockRenderFlags.skipRendering(selfFlags)) {
            return 0;
        }
        if (BlockRenderFlags.emptyOccluder(neighborFlags) || BlockRenderFlags.air(neighborFlags)) {
            return 1;
        }
        return -1;
    }

    public static boolean visible(final int mask, final Direction direction) {
        return mask >= 0 && (mask & 1 << direction.ordinal()) != 0;
    }

}
