package dev.ultima.meshing;

/**
 * Direct indexing for a section plus a 1-block halo. Interior cells are
 * {@code [0, 16)} in each axis; packed local coordinates are {@code [0, 18)}.
 *
 * <p>Packed layout is x-fastest, then y, then z, matching
 * {@code BlockPos.betweenClosed} on the interior.
 */
public final class SectionIndex {
    public static final int INTERIOR = 16;
    public static final int HALO = 1;
    public static final int EXTENT = INTERIOR + HALO * 2;
    public static final int VOLUME = EXTENT * EXTENT * EXTENT;
    public static final int STRIDE_Y = EXTENT;
    public static final int STRIDE_Z = EXTENT * EXTENT;

    private SectionIndex() {
    }

    public static int packed(final int localX, final int localY, final int localZ) {
        return localX + localY * STRIDE_Y + localZ * STRIDE_Z;
    }

    public static int interior(final int x, final int y, final int z) {
        return packed(x + HALO, y + HALO, z + HALO);
    }

    public static int neighbor(final int interiorX, final int interiorY, final int interiorZ, final int dx, final int dy, final int dz) {
        return packed(interiorX + HALO + dx, interiorY + HALO + dy, interiorZ + HALO + dz);
    }

    public static boolean inExtent(final int localX, final int localY, final int localZ) {
        return localX >= 0 && localX < EXTENT && localY >= 0 && localY < EXTENT && localZ >= 0 && localZ < EXTENT;
    }

    public static boolean inInterior(final int x, final int y, final int z) {
        return x >= 0 && x < INTERIOR && y >= 0 && y < INTERIOR && z >= 0 && z < INTERIOR;
    }

    public static int localX(final int packedIndex) {
        return packedIndex % EXTENT;
    }

    public static int localY(final int packedIndex) {
        return packedIndex / EXTENT % EXTENT;
    }

    public static int localZ(final int packedIndex) {
        return packedIndex / STRIDE_Z;
    }

    public static int worldX(final int originX, final int interiorX) {
        return originX + interiorX;
    }

    public static int haloWorldX(final int originX, final int localX) {
        return originX - HALO + localX;
    }
}
