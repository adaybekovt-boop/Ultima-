package dev.ultima.temporal;

/**
 * Maps a temporal mode to an internal render size. Unsupported modes must not
 * silently lower resolution — that would fake the native +25% FPS KPI.
 */
public final class TemporalResolution {
    private TemporalResolution() {
    }

    public static RenderSize recommended(final TemporalMode mode, final int outputWidth, final int outputHeight) {
        TemporalMode resolved = mode == null || !mode.isSupported() ? TemporalMode.NATIVE : mode;
        if (resolved.isNative()) {
            return new RenderSize(outputWidth, outputHeight);
        }
        return new RenderSize(outputWidth, outputHeight);
    }

    public static boolean isNativeResolution(final TemporalMode mode, final int renderWidth, final int renderHeight,
            final int outputWidth, final int outputHeight) {
        RenderSize recommended = recommended(mode, outputWidth, outputHeight);
        return recommended.width() == renderWidth && recommended.height() == renderHeight
                && renderWidth == outputWidth && renderHeight == outputHeight;
    }
}
