package dev.ultima.fsr;

/**
 * Last-line refuse so FSR never hijacks {@code mainRenderTarget} if
 * {@code beginWorldPass} runs while Iris is loaded.
 *
 * <p>That is not the live Iris protection. When Iris is present,
 * {@link FsrCompatibility#blocks(String)} disables {@code fsr_upscaling},
 * and {@link dev.ultima.config.UltimaMixinPlugin#shouldApplyMixin} skips
 * every mixin in the {@code fsr_upscaling} package, including
 * {@code GameRendererMixin}. {@code beginWorldPass} is therefore not called.
 * This gate remains for tests ({@code runWithLoadedModsForTest} after mixins
 * already applied) and as a last-line refuse if the mixin was applied without Iris
 * and Iris later appears in a hypothetical loader that could change the
 * loaded-mod set — which Fabric does not.
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
