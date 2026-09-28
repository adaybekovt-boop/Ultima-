package dev.ultima.config.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrCompatibility;

/**
 * Player-facing explanations for why a module is inactive. Machine-readable
 * {@code reason} values still come from {@link UltimaConfig#resolve(String)}.
 *
 * <p>This class owns the English text. The client screen translates the same messages through
 * {@code ultima.status.*} keys, and {@code LocalizationChecks} keeps the two in step.
 */
public final class ModuleDisableMessages {
    public static final String INCOMPATIBLE_MOD_REASON = "incompatible_mod";
    public static final String DEPENDENCY_DISABLED_REASON = "dependency_disabled";

    public static final String NO_INCOMPATIBLE_MOD_MESSAGE = "Disabled: an incompatible mod is loaded.";
    public static final String CONFLICT_OTHER_TEMPLATE =
            "Disabled: %1$s detected — this module conflicts with %1$s";
    public static final String CONFLICT_MULTIPLE_TEMPLATE =
            "Disabled: %1$s detected — this module conflicts with those mods";
    public static final String DEPENDENCY_TEMPLATE = "Inactive: required option \"%1$s\" is off.";
    public static final String ANOTHER_MODULE_NAME = "another module";

    /** Messages that carry no arguments, keyed by the {@code resolve()} reason. */
    private static final Map<String, String> STATIC_MESSAGES = new LinkedHashMap<>();
    /** Single-mod conflict messages, keyed by the Fabric mod id. */
    private static final Map<String, String> KNOWN_CONFLICTS = new LinkedHashMap<>();

    static {
        STATIC_MESSAGES.put("enabled", "Active: Mixins for this module were applied at launch.");
        STATIC_MESSAGES.put(FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK,
                "Disabled: no safe post-Iris integration point. IrisApi has no official "
                        + "hook after its shader final pass; FSR stays off so Iris keeps working.");
        STATIC_MESSAGES.put(FsrCompatibility.REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE,
                "Disabled: Iris internal resolution is not controllable from Ultima, so "
                        + "upscaling would have no effect.");
        STATIC_MESSAGES.put("not_client_environment",
                "Disabled: this module is client-only and is not available on a dedicated server.");
        STATIC_MESSAGES.put("disabled_by_config", "Off: turned off in ultima.properties.");
        STATIC_MESSAGES.put("disabled_by_default", "Off: this module is opt-in and was not requested.");
        STATIC_MESSAGES.put("dependency_cycle",
                "Disabled: this module is inactive because of a dependency cycle.");
        STATIC_MESSAGES.put("unknown_module", "Disabled: unknown module names fail closed to vanilla.");

        KNOWN_CONFLICTS.put("sodium",
                "Disabled: Sodium detected — this module conflicts with Sodium's renderer");
        KNOWN_CONFLICTS.put("iris",
                "Disabled: Iris detected — this module conflicts with Iris shaders");
        KNOWN_CONFLICTS.put("canvas",
                "Disabled: Canvas detected — this module conflicts with Canvas's renderer");
        KNOWN_CONFLICTS.put("lithium",
                "Disabled: Lithium detected — this module conflicts with Lithium's collision and entity optimizations");
        KNOWN_CONFLICTS.put("canary",
                "Disabled: Canary detected — this module conflicts with Canary's collision and entity optimizations");
        KNOWN_CONFLICTS.put("radium",
                "Disabled: Radium detected — this module conflicts with Radium's collision and entity optimizations");
    }

    private ModuleDisableMessages() {
    }

    public static boolean isHardLock(final UltimaConfig.ResolvedModule resolved) {
        return INCOMPATIBLE_MOD_REASON.equals(resolved.reason())
                || "not_client_environment".equals(resolved.reason())
                || FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK.equals(resolved.reason())
                || FsrCompatibility.REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE.equals(resolved.reason());
    }

    /** @return the argument-free messages keyed by {@code resolve()} reason (read-only view) */
    public static Map<String, String> staticMessages() {
        return Map.copyOf(STATIC_MESSAGES);
    }

    /** @return the single-mod conflict messages keyed by Fabric mod id (read-only view) */
    public static Map<String, String> knownConflictMessages() {
        return Map.copyOf(KNOWN_CONFLICTS);
    }

    public static String playerFacing(final UltimaConfig.ResolvedModule resolved) {
        String reason = resolved.reason();
        if (INCOMPATIBLE_MOD_REASON.equals(reason)) {
            return conflict(resolved.loadedIncompatibleMods());
        }
        if (DEPENDENCY_DISABLED_REASON.equals(reason)) {
            return dependency(resolved.blockingDependency());
        }
        String fixed = STATIC_MESSAGES.get(reason);
        if (fixed != null) {
            return fixed;
        }
        return resolved.detail() == null || resolved.detail().isBlank()
                ? reason
                : reason + ": " + resolved.detail();
    }

    public static String conflict(final List<String> loadedIncompatibleMods) {
        if (loadedIncompatibleMods == null || loadedIncompatibleMods.isEmpty()) {
            return NO_INCOMPATIBLE_MOD_MESSAGE;
        }
        if (loadedIncompatibleMods.size() == 1) {
            return singleConflict(loadedIncompatibleMods.getFirst());
        }
        return String.format(CONFLICT_MULTIPLE_TEMPLATE, joinDisplayNames(loadedIncompatibleMods));
    }

    private static String singleConflict(final String modId) {
        String known = KNOWN_CONFLICTS.get(modId);
        return known != null ? known : String.format(CONFLICT_OTHER_TEMPLATE, displayModName(modId));
    }

    private static String dependency(final String blockingDependency) {
        String name = blockingDependency == null
                ? ANOTHER_MODULE_NAME
                : UltimaSettingsCatalog.displayName(blockingDependency);
        return String.format(DEPENDENCY_TEMPLATE, name);
    }

    public static String joinDisplayNames(final List<String> modIds) {
        List<String> names = new ArrayList<>(modIds.size());
        for (String modId : modIds) {
            names.add(displayModName(modId));
        }
        return String.join(", ", names);
    }

    public static String displayModName(final String modId) {
        if (modId == null || modId.isBlank()) {
            return "Unknown mod";
        }
        return modId.substring(0, 1).toUpperCase(Locale.ROOT) + modId.substring(1);
    }
}
