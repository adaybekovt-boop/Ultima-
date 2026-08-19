package dev.ultima.fsr;

/**
 * Last-line runtime gate so FSR never hijacks {@code mainRenderTarget} while
 * Iris is loaded. Config/Mixin gating should already have disabled the module;
 * this refuses the world-pass redirect if that gate is bypassed.
 *
 * <p>Fail-open direction: FSR stays off, Iris keeps rendering. Never the reverse.
 */
public final class FsrRuntimeGate {
    private FsrRuntimeGate() {
    }

    public static boolean allowWorldTargetHijack(
            final boolean moduleEnabled,
            final boolean failedOpen,
            final boolean irisLoaded) {
        return moduleEnabled && !failedOpen && !irisLoaded;
    }
}
