package dev.ultima.fsr;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability-based conflict policy for {@code fsr_upscaling}. This is not a
 * blanket "any renderer mod → disable" switch.

 * <p>{@link #disablingModIds()} is the single list {@code UltimaModules} registers
 * as {@code incompatibleMods}. {@link dev.ultima.config.UltimaConfig#resolve(String)}
 * and the settings UI both read that list; they do not re-evaluate this class.
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

    /**
     * Mod ids that auto-disable {@code fsr_upscaling}. Order matches
     * {@link #evaluate(boolean, boolean, boolean)} (Iris before Canvas).
     * {@link dev.ultima.config.UltimaModules} copies this into the module
     * registry so Mixin gating, {@code resolve()}, and the settings screen
     * share one source of truth.
     */
    public static List<String> disablingModIds() {
        List<String> ids = new ArrayList<>();
        for (DisableReason reason : DisableReason.values()) {
            if (reason.modId() != null) {
                ids.add(reason.modId());
            }
        }
        return List.copyOf(ids);
    }
}
