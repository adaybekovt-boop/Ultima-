package dev.ultima.client.renderer.retained;

/**
 * Bit-identical packing for GPU section records. Origin stays integer block
 * coordinates; visibility is the same IEEE float vanilla writes into the UBO.
 */
public final class SectionPacking {
    private SectionPacking() {
    }

    public static int visibilityBits(final float visibility) {
        return Float.floatToIntBits(visibility);
    }

    public static float visibilityFromBits(final int bits) {
        return Float.intBitsToFloat(bits);
    }

    public static void writeIVec4(
            final int[] dest,
            final int index,
            final int originX,
            final int originY,
            final int originZ,
            final float visibility) {
        int base = index * 4;
        dest[base] = originX;
        dest[base + 1] = originY;
        dest[base + 2] = originZ;
        dest[base + 3] = visibilityBits(visibility);
    }

    public static boolean originsMatch(final int x, final int y, final int z, final int packedX, final int packedY, final int packedZ) {
        return x == packedX && y == packedY && z == packedZ;
    }
}
