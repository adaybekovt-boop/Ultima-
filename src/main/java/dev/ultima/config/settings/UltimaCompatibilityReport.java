package dev.ultima.config.settings;

import java.util.List;

import dev.ultima.config.UltimaConfig;

/**
 * Machine-readable dump of {@link UltimaConfig#resolvedModules()}. The settings
 * screen and {@code /ultima debug compatibility} both use this so disable reasons
 * stay in one place.
 */
public final class UltimaCompatibilityReport {
    private UltimaCompatibilityReport() {
    }

    public static String toJson(final UltimaConfig config) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"file\": ").append(quote(UltimaConfig.fileName())).append(",\n");
        json.append("  \"pendingRestart\": ").append(config.hasPendingRestart()).append(",\n");
        json.append("  \"modules\": [\n");
        List<UltimaConfig.ResolvedModule> modules = config.resolvedModules();
        for (int index = 0; index < modules.size(); index++) {
            appendModule(json, modules.get(index), config);
            if (index + 1 < modules.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    public static String toChatLines(final UltimaConfig config) {
        StringBuilder text = new StringBuilder();
        text.append("Ultima compatibility (").append(config.knownModuleCount()).append(" modules)\n");
        for (UltimaConfig.ResolvedModule module : config.resolvedModules()) {
            text.append(module.key())
                    .append(" requested=")
                    .append(module.requested())
                    .append(" enabled=")
                    .append(module.enabled())
                    .append(" reason=")
                    .append(module.reason());
            if (ModuleDisableMessages.isHardLock(module)) {
                text.append(" lock=").append(ModuleDisableMessages.playerFacing(module));
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static void appendModule(
            final StringBuilder json,
            final UltimaConfig.ResolvedModule module,
            final UltimaConfig config) {
        json.append("    {\n");
        field(json, "key", module.key(), true);
        field(json, "requested", module.requested(), true);
        field(json, "enabled", module.enabled(), true);
        field(json, "enabledByDefault", module.enabledByDefault(), true);
        field(json, "clientOnly", module.clientOnly(), true);
        field(json, "appliedAtLaunch", config.wasEnabledAtLaunch(module.key()), true);
        field(json, "pendingRestart", config.hasPendingRestart(module.key()), true);
        field(json, "reason", module.reason(), true);
        field(json, "detail", module.detail(), true);
        field(json, "playerFacing", ModuleDisableMessages.playerFacing(module), true);
        field(json, "moduleClass", module.moduleClass(), true);
        field(json, "blockingDependency", module.blockingDependency(), true);
        json.append("      \"dependencies\": ").append(stringArray(module.dependencies())).append(",\n");
        json.append("      \"incompatibleMods\": ").append(stringArray(module.incompatibleMods())).append(",\n");
        json.append("      \"loadedIncompatibleMods\": ").append(stringArray(module.loadedIncompatibleMods())).append('\n');
        json.append("    }");
    }

    private static void field(final StringBuilder json, final String name, final String value, final boolean comma) {
        json.append("      ").append(quote(name)).append(": ");
        if (value == null) {
            json.append("null");
        } else {
            json.append(quote(value));
        }
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void field(final StringBuilder json, final String name, final boolean value, final boolean comma) {
        json.append("      ").append(quote(name)).append(": ").append(value);
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String stringArray(final List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append(quote(values.get(index)));
        }
        json.append(']');
        return json.toString();
    }

    private static String quote(final String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 32) {
                        json.append(String.format("\\u%04x", (int)character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
        return json.toString();
    }
}
