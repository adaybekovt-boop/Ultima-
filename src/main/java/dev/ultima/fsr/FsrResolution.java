package dev.ultima.fsr;

/**
 * Maps a native (window / swapchain) size and an FSR1 preset to the internal
 * render size. Rounding matches AMD's integer conversion
 * {@code (uint32_t)(display / ratio + 0.5)}, which is {@code round(native * scale)}.
 *
 * <p>Internal size is clamped to {@code [1, native]} so a 1-pixel window cannot
 * become 0 and FSR1 never silently super-samples above native.
 */
public final class FsrResolution {
    private FsrResolution() {
    }

    public static FsrSize internal(final FsrQualityPreset preset, final int nativeWidth, final int nativeHeight) {
        FsrQualityPreset resolved = preset == null ? FsrQualityPreset.defaultPreset() : preset;
        return new FsrSize(
                scaleAxis(nativeWidth, resolved.scaleFactor()),
                scaleAxis(nativeHeight, resolved.scaleFactor()));
    }

    public static int scaleAxis(final int nativePixels, final double scaleFactor) {
        int nativeSize = Math.max(1, nativePixels);
        if (!(scaleFactor > 0.0) || Double.isNaN(scaleFactor) || Double.isInfinite(scaleFactor)) {
            return nativeSize;
        }
        long rounded = Math.round(nativeSize * scaleFactor);
        if (rounded < 1L) {
            return 1;
        }
        if (rounded > nativeSize) {
            return nativeSize;
        }
        return (int)rounded;
    }

    public static boolean isNativeResolution(final FsrSize internal, final int nativeWidth, final int nativeHeight) {
        return internal != null && internal.equalsSize(Math.max(1, nativeWidth), Math.max(1, nativeHeight));
    }
}
