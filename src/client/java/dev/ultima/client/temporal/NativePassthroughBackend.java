package dev.ultima.client.temporal;

import dev.ultima.temporal.MotionVectorSemantic;
import dev.ultima.temporal.RenderSize;
import dev.ultima.temporal.TemporalMode;
import dev.ultima.temporal.TemporalResolution;

/**
 * Native-resolution passthrough. Recommended size equals output size. Evaluate
 * is a no-op: the world already sits in the main color/depth targets, and HUD
 * continues to composite on top at the same resolution.
 */
public final class NativePassthroughBackend implements TemporalBackend {
    @Override
    public TemporalMode mode() {
        return TemporalMode.NATIVE;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public void initialize() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public RenderSize getRecommendedRenderSize(final int outputWidth, final int outputHeight) {
        return TemporalResolution.recommended(TemporalMode.NATIVE, outputWidth, outputHeight);
    }

    @Override
    public void beginFrame(final TemporalFrameData frame) {
        frame.mode = TemporalMode.NATIVE;
        frame.currentJitterX = 0.0F;
        frame.currentJitterY = 0.0F;
        RenderSize size = this.getRecommendedRenderSize(frame.outputWidth, frame.outputHeight);
        frame.renderWidth = size.width();
        frame.renderHeight = size.height();
        if (frame.hasPrevious && !frame.resetThisFrame) {
            frame.motionVectorPlan = MotionVectorSemantic.STATIC_WORLD_CAMERA_ONLY;
        } else {
            frame.motionVectorPlan = MotionVectorSemantic.UNAVAILABLE;
        }
        frame.motionVectors = null;
    }

    @Override
    public void evaluate(final TemporalFrameData frame) {
        frame.evaluatedThisFrame = true;
    }

    @Override
    public void resetHistory() {
    }
}
