package dev.ultima.fsr;

/**
 * When FSR is redirecting, vanilla {@code tryTakeScreenshotIfNeeded} runs after
 * {@code renderLevel} and before RCAS. {@code takeAutoScreenshot} reads the
 * {@code mainRenderTarget} field from a method that was not in the GETFIELD
 * redirect list, so it captured empty vanilla main. Defer the call until after
 * RCAS writes the upscaled frame to vanilla main.
 */
public final class FsrScreenshotPolicy {
    public enum Action {
        TAKE_NOW,
        DEFER_UNTIL_AFTER_RCAS
    }

    private FsrScreenshotPolicy() {
    }

    public static Action onVanillaScreenshotCall(final boolean worldPassActive) {
        return worldPassActive ? Action.DEFER_UNTIL_AFTER_RCAS : Action.TAKE_NOW;
    }

    public static boolean captureAfterRcas(final boolean pending, final boolean upscaleSucceeded) {
        return pending && upscaleSucceeded;
    }
}
