package dev.ultima.util;

/**
 * Overflow-safe range checks used by direct section-key iteration.
 *
 * <p>{@link #preferVanillaSectionWalk(long, int)} is the shared probe-budget
 * decision used by both {@code entity_section_lookup} and
 * {@code entity_query_early_out}. A dense key walk of a huge AABB is more
 * expensive than vanilla's strip walk of the already-loaded section map.
 */
public final class SectionRangeMath {
    public static final long DIRECT_LOOKUP_BUDGET = 1024L;
    private static final int MIN_PACKED_XZ = -(1 << 21);
    private static final int MAX_PACKED_XZ = (1 << 21) - 1;
    private static final int MIN_PACKED_Y = -(1 << 19);
    private static final int MAX_PACKED_Y = (1 << 19) - 1;

    private SectionRangeMath() {
    }

    public static boolean isPackable(
            final int xMin,
            final int yMin,
            final int zMin,
            final int xMax,
            final int yMax,
            final int zMax) {
        return xMin >= MIN_PACKED_XZ
                && xMax <= MAX_PACKED_XZ
                && zMin >= MIN_PACKED_XZ
                && zMax <= MAX_PACKED_XZ
                && yMin >= MIN_PACKED_Y
                && yMax <= MAX_PACKED_Y;
    }

    public static long saturatedVolume(
            final int xMin,
            final int yMin,
            final int zMin,
            final int xMax,
            final int yMax,
            final int zMax) {
        long xSpan = (long)xMax - xMin + 1L;
        long ySpan = (long)yMax - yMin + 1L;
        long zSpan = (long)zMax - zMin + 1L;
        if (xSpan <= 0L || ySpan <= 0L || zSpan <= 0L) {
            return 0L;
        }

        return saturatedMultiply(saturatedMultiply(xSpan, ySpan), zSpan);
    }

    /**
     * @return {@code true} when a dense section-key probe should be skipped in
     *         favour of vanilla's walk of the loaded {@code sections} map
     */
    public static boolean preferVanillaSectionWalk(final long packedVolume, final int loadedSectionCount) {
        return packedVolume > DIRECT_LOOKUP_BUDGET || packedVolume > loadedSectionCount;
    }

    private static long saturatedMultiply(final long left, final long right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
