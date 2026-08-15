package dev.ultima.client.temporal;

import dev.ultima.temporal.RenderSize;
import dev.ultima.temporal.TemporalMode;

/**
 * Vendor-neutral temporal evaluate contract. Terrain produces
 * {@link TemporalFrameData}; backends consume it.
 */
public interface TemporalBackend {
    TemporalMode mode();

    boolean isSupported();

    void initialize();

    void shutdown();

    RenderSize getRecommendedRenderSize(final int outputWidth, final int outputHeight);

    void beginFrame(final TemporalFrameData frame);

    /**
     * Reconstruct or pass through the world color buffer. Must not touch HUD.
     * Native passthrough must not blit or scale.
     */
    void evaluate(final TemporalFrameData frame);

    void resetHistory();
}
