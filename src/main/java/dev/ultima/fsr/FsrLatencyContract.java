package dev.ultima.fsr;

/**
 * Architectural latency contract for Ultima FSR1.
 *
 * <p>FSR1 is one spatial EASU pass plus one spatial RCAS pass on the current
 * finished world frame. It does not keep frame history, does not interpolate
 * a held-back frame, and does not wait for the next frame before presenting
 * the current one. Frame Generation is intentionally not implemented.
 *
 * <p>The only added cost, when the pass actually runs, is GPU time for those
 * two fullscreen draws. That is <em>not</em> server tick delay and not
 * input-packet lag. Extra input latency is at most the GPU duration of the
 * pass (a fraction of a frame), never an extra displayed frame of hold-back.
 *
 * <p>This class records the contract. It does not invent GPU milliseconds.
 * Measuring EASU+RCAS vs total frame time requires a real GPU.
 */
public final class FsrLatencyContract {
    public static final String ADDED_COST =
            "single EASU+RCAS GPU pass on the current frame; no queued extra frame";

    private FsrLatencyContract() {
    }

    public static boolean usesTemporalHistory() {
        return false;
    }

    public static boolean holdsCurrentFrameForInterpolation() {
        return false;
    }

    public static int extraDisplayedFramesOfDelay() {
        return 0;
    }

    /**
     * When Iris is loaded, FSR does not run (no official hook / no internal
     * resolution control). Added GPU pass time is therefore zero.
     */
    public static boolean addsGpuPassWhenIrisPresent() {
        return false;
    }
}
