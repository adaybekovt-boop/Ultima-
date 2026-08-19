package dev.ultima.fsr;

import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Capability-based policy for {@code fsr_upscaling}.
 *
 * <p>{@link #unconditionalIncompatibleModIds()} is what {@code UltimaModules}
 * registers as {@code incompatibleMods}. Only Canvas is on that list: it owns
 * the renderer. Sodium is <em>not</em> an unconditional FSR conflict (the
 * existing {@code GameRenderer} world-pass hook can still sit after Sodium
 * terrain). Iris is also not on that list: when Iris is loaded, FSR is still
 * disabled, but through {@link DisableReason#NO_SAFE_POST_IRIS_INTEGRATION_POINT}
 * (and the documented internal-resolution limitation), not {@code incompatible_mod}.
 *
 * <p>{@code retained_terrain} and {@code mesher_fast_path} keep their own
 * Sodium/Iris/Canvas auto-disable list. This class does not change them.
 */
public final class FsrCompatibility {
    public static final String REASON_NO_SAFE_POST_IRIS_HOOK = "no_safe_post_iris_integration_point";
    public static final String REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE =
            "iris_internal_resolution_not_controllable";

    public static final String DETAIL_NO_SAFE_POST_IRIS_HOOK =
            "Iris is loaded, but IrisApi v0 has no official hook after the shader final "
                    + "program and before GUI (Iris #2947). Ultima also cannot set Iris "
                    + "internal render-target resolution from outside, so a native-res blit "
                    + "would only sharpen and would not be real upscaling. FSR stays off so "
                    + "Iris keeps working.";

    public static final String DETAIL_IRIS_RESOLUTION_NOT_CONTROLLABLE =
            "Iris internal resolution is not controllable from Ultima, so upscaling would "
                    + "have no effect (native-res sharpening is not shipped as FSR).";

    public static final String DETAIL_CANVAS =
            "Canvas owns the renderer; FSR is not applied.";

    private static final ThreadLocal<boolean[]> TEST_LOADED_MODS = new ThreadLocal<>();

    public enum DisableReason {
        NONE(null, null, null),
        CANVAS_OWNS_RENDERER("incompatible_mod", "canvas", DETAIL_CANVAS),
        NO_SAFE_POST_IRIS_INTEGRATION_POINT(
                REASON_NO_SAFE_POST_IRIS_HOOK, "iris", DETAIL_NO_SAFE_POST_IRIS_HOOK),
        IRIS_INTERNAL_RESOLUTION_NOT_CONTROLLABLE(
                REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE,
                "iris",
                DETAIL_IRIS_RESOLUTION_NOT_CONTROLLABLE);

        private final String configReason;
        private final String modId;
        private final String detail;

        DisableReason(final String configReason, final String modId, final String detail) {
            this.configReason = configReason;
            this.modId = modId;
            this.detail = detail;
        }

        public String configReason() {
            return this.configReason;
        }

        public String modId() {
            return this.modId;
        }

        public String detail() {
            return this.detail;
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
    public static DisableReason evaluate(
            final boolean irisLoaded, final boolean canvasLoaded, final boolean sodiumLoaded) {
        if (canvasLoaded) {
            return DisableReason.CANVAS_OWNS_RENDERER;
        }
        if (irisLoaded) {
            if (!FsrIrisCapabilities.hasOfficialPostProcessHook()) {
                return DisableReason.NO_SAFE_POST_IRIS_INTEGRATION_POINT;
            }
            if (!FsrIrisCapabilities.canControlInternalResolution()) {
                return DisableReason.IRIS_INTERNAL_RESOLUTION_NOT_CONTROLLABLE;
            }
            return DisableReason.NONE;
        }
        // Sodium-only is allowed: it does not own the post-world GameRenderer path.
        return DisableReason.NONE;
    }

    public static DisableReason current() {
        return evaluate(irisLoaded(), canvasLoaded(), sodiumLoaded());
    }

    public static boolean blocks(final String moduleKey) {
        return "fsr_upscaling".equals(moduleKey) && current().disables();
    }

    /**
     * Sodium-only is allowed. Sodium+Iris is not, because the Iris capability
     * gates fail.
     */
    public static boolean allowsWithSodiumOnly(
            final boolean sodiumLoaded, final boolean irisLoaded, final boolean canvasLoaded) {
        return sodiumLoaded
                && !irisLoaded
                && !canvasLoaded
                && evaluate(irisLoaded, canvasLoaded, sodiumLoaded) == DisableReason.NONE;
    }

    /**
     * Mod ids that unconditionally auto-disable {@code fsr_upscaling} via
     * {@code incompatible_mod}. Iris is handled by {@link #current()} instead.
     */
    public static List<String> disablingModIds() {
        return unconditionalIncompatibleModIds();
    }

    public static List<String> unconditionalIncompatibleModIds() {
        return List.of("canvas");
    }

    /**
     * Runs {@code action} as if the given renderer mods were loaded. Tests only.
     */
    public static void runWithLoadedModsForTest(
            final boolean irisLoaded,
            final boolean canvasLoaded,
            final boolean sodiumLoaded,
            final Runnable action) {
        TEST_LOADED_MODS.set(new boolean[] {irisLoaded, canvasLoaded, sodiumLoaded});
        try {
            action.run();
        } finally {
            TEST_LOADED_MODS.remove();
        }
    }

    static Boolean testIrisLoaded() {
        boolean[] override = TEST_LOADED_MODS.get();
        return override == null ? null : override[0];
    }

    private static boolean irisLoaded() {
        Boolean override = testIrisLoaded();
        return override != null ? override : isModLoaded("iris");
    }

    private static boolean canvasLoaded() {
        boolean[] override = TEST_LOADED_MODS.get();
        if (override != null) {
            return override[1];
        }
        return isModLoaded("canvas");
    }

    private static boolean sodiumLoaded() {
        boolean[] override = TEST_LOADED_MODS.get();
        if (override != null) {
            return override[2];
        }
        return isModLoaded("sodium");
    }

    private static boolean isModLoaded(final String modId) {
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
