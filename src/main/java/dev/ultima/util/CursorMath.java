package dev.ultima.util;

/**
 * Preconditions under which an int-backed {@code Cursor3D} completes before its index wraps.
 */
public final class CursorMath {
    private CursorMath() {
    }

    public static boolean canUseCarry(final int width, final int height, final int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            return false;
        }

        // A product of two positive ints fits in a long; a product of three can wrap it.
        long area = (long)width * height;
        if (area > Integer.MAX_VALUE) {
            return false;
        }
        return area * depth <= Integer.MAX_VALUE;
    }
}
