package dev.ultima.fsr;

/**
 * Capability-based conflict policy for {@code fsr_upscaling}. This is not a
 * blanket "any renderer mod → disable" switch.
 *
 * <ul>
 *   <li><b>Iris</b> owns the shader / post-process / framebuffer pipeline. FSR
 *       cannot share the final color target with it.</li>
 *   <li><b>Canvas</b> replaces the renderer and owns its own targets.</li>
 *   <li><b>Sodium without Iris</b> replaces terrain meshing/submission. It does
 *       not, by itself, own GameRenderer's post-world output stage in the 26.2
 *       vanilla pipeline Ultima hooks. FSR stays allowed. Residual risk: a
 *       future Sodium build that presents into a private output RT.</li>
 * </ul>
 */
public final class FsrCompatibility {
    public enum DisableReason {
        NONE(null),
        IRIS_OWNS_POST_PROCESS("iris"),
        CANVAS_OWNS_RENDERER("canvas");

        private final String modId;

        DisableReason(final String modId) {
            this.modId = modId;
        }

        public String modId() {
            return this.modId;
        }

        public boolean disables() {
            return this != NONE;
        }
    }

    private FsrCompatibility() {
    }

    /**
     * @param irisLoaded whether the {@code iris} mod id is loaded
     * @param canvasLoaded whether the {@code canvas} mod id is loaded
     * @param sodiumLoaded whether the {@code sodium} mod id is loaded (informational;
     *        does not disable FSR by itself)
     */
    public static DisableReason evaluate(final boolean irisLoaded, final boolean canvasLoaded, final boolean sodiumLoaded) {
        if (irisLoaded) {
            return DisableReason.IRIS_OWNS_POST_PROCESS;
        }
        if (canvasLoaded) {
            return DisableReason.CANVAS_OWNS_RENDERER;
        }
        return DisableReason.NONE;
    }

    public static boolean allowsWithSodiumOnly(final boolean sodiumLoaded, final boolean irisLoaded, final boolean canvasLoaded) {
        return evaluate(irisLoaded, canvasLoaded, sodiumLoaded) == DisableReason.NONE;
    }
}
