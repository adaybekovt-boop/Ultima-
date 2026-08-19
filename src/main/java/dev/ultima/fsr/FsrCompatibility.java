package dev.ultima.fsr;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderer-conflict policy for {@code fsr_upscaling}.
 *
 * <p>{@link #disablingModIds()} is the single list {@code UltimaModules} registers
 * as {@code incompatibleMods}. {@link dev.ultima.config.UltimaConfig#resolve(String)}
 * and the settings UI both read that list; they do not maintain a second copy.
 * Sodium, Iris, and Canvas all disable this implementation so FSR never competes
 * with another renderer integration for the final world/post-process path.
 */
public final class FsrCompatibility {
    public enum DisableReason {
        NONE(null),
        SODIUM_OWNS_RENDERER("sodium"),
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
     * @param sodiumLoaded whether the {@code sodium} mod id is loaded
     */
    public static DisableReason evaluate(final boolean irisLoaded, final boolean canvasLoaded, final boolean sodiumLoaded) {
        if (irisLoaded) {
            return DisableReason.IRIS_OWNS_POST_PROCESS;
        }
        if (canvasLoaded) {
            return DisableReason.CANVAS_OWNS_RENDERER;
        }
        if (sodiumLoaded) {
            return DisableReason.SODIUM_OWNS_RENDERER;
        }
        return DisableReason.NONE;
    }

    /**
     * Kept for source compatibility with the original FSR branch. Sodium-only is
     * now deliberately rejected by the merged renderer conflict contract.
     */
    public static boolean allowsWithSodiumOnly(final boolean sodiumLoaded, final boolean irisLoaded, final boolean canvasLoaded) {
        return evaluate(irisLoaded, canvasLoaded, sodiumLoaded) == DisableReason.NONE;
    }

    /**
     * Mod ids that auto-disable {@code fsr_upscaling}.
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
