package dev.ultima.config.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrCompatibility;

/**
 * Player-facing explanations for why a module is inactive. Machine-readable
 * {@code reason} values still come from {@link UltimaConfig#resolve(String)}.
 */
public final class ModuleDisableMessages {
    private ModuleDisableMessages() {
    }

    public static boolean isHardLock(final UltimaConfig.ResolvedModule resolved) {
        return "incompatible_mod".equals(resolved.reason())
                || "not_client_environment".equals(resolved.reason())
                || FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK.equals(resolved.reason())
                || FsrCompatibility.REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE.equals(resolved.reason());
    }

    public static String playerFacing(final UltimaConfig.ResolvedModule resolved) {
        return switch (resolved.reason()) {
            case "enabled" -> "Active: Mixins for this module were applied at launch.";
            case "incompatible_mod" -> conflict(resolved.loadedIncompatibleMods());
            case FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK ->
                    "Disabled: no safe post-Iris integration point. IrisApi has no official "
                            + "hook after its shader final pass; FSR stays off so Iris keeps working.";
            case FsrCompatibility.REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE ->
                    "Disabled: Iris internal resolution is not controllable from Ultima, so "
                            + "upscaling would have no effect.";
            case "not_client_environment" ->
                    "Disabled: this module is client-only and is not available on a dedicated server.";
            case "disabled_by_config" -> "Off: turned off in ultima.properties.";
            case "disabled_by_default" -> "Off: this module is opt-in and was not requested.";
            case "dependency_disabled" -> dependency(resolved.blockingDependency());
            case "dependency_cycle" -> "Disabled: this module is inactive because of a dependency cycle.";
            case "unknown_module" -> "Disabled: unknown module names fail closed to vanilla.";
            default -> resolved.detail() == null || resolved.detail().isBlank()
                    ? resolved.reason()
                    : resolved.reason() + ": " + resolved.detail();
        };
    }

    public static String conflict(final List<String> loadedIncompatibleMods) {
        if (loadedIncompatibleMods == null || loadedIncompatibleMods.isEmpty()) {
            return "Disabled: an incompatible mod is loaded.";
        }
        if (loadedIncompatibleMods.size() == 1) {
            return singleConflict(loadedIncompatibleMods.getFirst());
        }
        return "Disabled: "
                + joinDisplayNames(loadedIncompatibleMods)
                + " detected — this module conflicts with those mods";
    }

    private static String singleConflict(final String modId) {
        return switch (modId) {
            case "sodium" -> "Disabled: Sodium detected — this module conflicts with Sodium's renderer";
            case "iris" -> "Disabled: Iris detected — this module conflicts with Iris shaders";
            case "canvas" -> "Disabled: Canvas detected — this module conflicts with Canvas's renderer";
            case "lithium" ->
                    "Disabled: Lithium detected — this module conflicts with Lithium's collision and entity optimizations";
            case "canary" ->
                    "Disabled: Canary detected — this module conflicts with Canary's collision and entity optimizations";
            case "radium" ->
                    "Disabled: Radium detected — this module conflicts with Radium's collision and entity optimizations";
            default -> "Disabled: " + displayMod(modId) + " detected — this module conflicts with "
                    + displayMod(modId);
        };
    }

    private static String dependency(final String blockingDependency) {
        String name = blockingDependency == null
                ? "another module"
                : UltimaSettingsCatalog.displayName(blockingDependency);
        return "Inactive: required option \"" + name + "\" is off.";
    }

    private static String joinDisplayNames(final List<String> modIds) {
        List<String> names = new ArrayList<>(modIds.size());
        for (String modId : modIds) {
            names.add(displayMod(modId));
        }
        return String.join(", ", names);
    }

    private static String displayMod(final String modId) {
        if (modId == null || modId.isBlank()) {
            return "Unknown mod";
        }
        return modId.substring(0, 1).toUpperCase(Locale.ROOT) + modId.substring(1);
    }
}
