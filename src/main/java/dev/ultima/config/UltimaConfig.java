package dev.ultima.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultima's optimization modules are individually switchable so that a single incompatible
 * optimization can be disabled without giving up the rest of the mod.
 *
 * <p>The configuration is read before Mixins are applied and must therefore not touch Minecraft
 * classes during load. In-game settings write the same {@code ultima.properties} file immediately,
 * but Mixin application still follows the values that were resolved at launch.
 */
public final class UltimaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-config");
    private static final String FILE_NAME = "ultima.properties";

    private static volatile UltimaConfig instance;

    private final Map<String, Boolean> modules;
    private FsrSettings fsrSettings;
    private final Map<String, Boolean> launchRequested;
    private final Map<String, Boolean> launchEnabled;

    private UltimaConfig(final Map<String, Boolean> modules) {
        this(modules, FsrSettings.defaults());
    }

    private UltimaConfig(final Map<String, Boolean> modules, final FsrSettings fsrSettings) {
        this.modules = modules;
        this.fsrSettings = fsrSettings == null ? FsrSettings.defaults() : fsrSettings;
        Map<String, Boolean> requested = new LinkedHashMap<>();
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            requested.put(module.key(), Boolean.TRUE.equals(modules.get(module.key())));
            enabled.put(module.key(), this.isEnabled(module.key()));
        }
        this.launchRequested = Map.copyOf(requested);
        this.launchEnabled = Map.copyOf(enabled);
    }

    public FsrSettings fsrSettings() {
        return this.fsrSettings;
    }

    /**
     * Builds a config from an explicit requested-state map. Used by tests and does not touch the
     * singleton or the config directory.
     */
    public static UltimaConfig createForTests(final Map<String, Boolean> modules) {
        return createForTests(modules, FsrSettings.defaults());
    }

    public static UltimaConfig createForTests(final Map<String, Boolean> modules, final FsrSettings fsrSettings) {
        return new UltimaConfig(new LinkedHashMap<>(modules), fsrSettings);
    }

    public static UltimaConfig get() {
        UltimaConfig local = instance;
        if (local == null) {
            synchronized (UltimaConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }

        return local;
    }

    /**
     * @return whether the module is enabled; known modules start from their declared default and
     *         unknown module names fail closed to vanilla behavior.
     */
    public boolean isEnabled(final String module) {
        return this.isEnabled(module, new HashSet<>());
    }

    private boolean isEnabled(final String module, final Set<String> resolving) {
        UltimaModules.Module definition = UltimaModules.byKey(module);
        if (definition == null
                || !isApplicableInCurrentEnvironment(definition)
                || hasLoadedIncompatibility(definition)
                || !Boolean.TRUE.equals(this.modules.get(module))
                || !resolving.add(module)) {
            return false;
        }

        try {
            for (String dependency : definition.dependencies()) {
                if (!this.isEnabled(dependency, resolving)) {
                    return false;
                }
            }
            return true;
        } finally {
            resolving.remove(module);
        }
    }

    public int enabledModuleCount() {
        int enabled = 0;
        for (String module : this.modules.keySet()) {
            if (this.isEnabled(module)) {
                enabled++;
            }
        }

        return enabled;
    }

    public int knownModuleCount() {
        int known = 0;
        for (UltimaModules.Module module : UltimaModules.all()) {
            if (isApplicableInCurrentEnvironment(module)) {
                known++;
            }
        }
        return known;
    }

    /**
     * @return whether {@code ultima.properties} requested the module, before environment,
     *         incompatibility, and dependency gates.
     */
    public boolean isRequested(final String module) {
        return Boolean.TRUE.equals(this.modules.get(module));
    }

    /**
     * Updates the requested flag for a known module. Does not apply Mixins; persist with
     * {@link #save()} or {@link #saveTo(Path)}.
     *
     * @return {@code false} when the name is not a registered module
     */
    public boolean setRequested(final String module, final boolean requested) {
        if (UltimaModules.byKey(module) == null) {
            return false;
        }
        this.modules.put(module, requested);
        return true;
    }

    /**
     * Updates the FSR1 quality preset stored in this config. Sharpness is unchanged.
     * Persist with {@link #save()} or {@link #saveTo(Path)}.
     */
    public void setFsrPreset(final FsrQualityPreset preset) {
        FsrSettings current = this.fsrSettings;
        this.fsrSettings = new FsrSettings(
                preset == null ? FsrQualityPreset.defaultPreset() : preset,
                current.sharpnessStops());
    }

    public boolean wasRequestedAtLaunch(final String module) {
        return Boolean.TRUE.equals(this.launchRequested.get(module));
    }

    public boolean wasEnabledAtLaunch(final String module) {
        return Boolean.TRUE.equals(this.launchEnabled.get(module));
    }

    public boolean hasPendingRestart(final String module) {
        return this.isRequested(module) != this.wasRequestedAtLaunch(module);
    }

    public boolean hasPendingRestart() {
        for (UltimaModules.Module module : UltimaModules.all()) {
            if (this.hasPendingRestart(module.key())) {
                return true;
            }
        }
        return false;
    }

    public static String fileName() {
        return FILE_NAME;
    }

    public static @org.jspecify.annotations.Nullable Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void save() {
        Path path = resolveConfigPath();
        if (path == null) {
            LOGGER.warn("Cannot save Ultima config; the config directory is unavailable.");
            return;
        }
        write(path, this.modules, this.fsrSettings);
    }

    public void saveTo(final Path path) {
        write(path, this.modules, this.fsrSettings);
    }

    public List<ResolvedModule> resolvedModules() {
        List<ResolvedModule> resolved = new ArrayList<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            resolved.add(this.resolve(module.key()));
        }
        return List.copyOf(resolved);
    }

    public ResolvedModule resolve(final String module) {
        UltimaModules.Module definition = UltimaModules.byKey(module);
        boolean requested = this.isRequested(module);
        boolean enabled = this.isEnabled(module);
        if (definition == null) {
            return new ResolvedModule(
                    module,
                    requested,
                    false,
                    false,
                    false,
                    "unknown_module",
                    "Unknown module names fail closed to vanilla.",
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    "unknown");
        }

        List<String> loadedIncompatible = loadedIncompatibleMods(definition);
        String blockingDependency = null;
        for (String dependency : definition.dependencies()) {
            if (!this.isEnabled(dependency)) {
                blockingDependency = dependency;
                break;
            }
        }

        String reason;
        String detail;
        if (enabled) {
            reason = "enabled";
            detail = "Mixins for this module are applied.";
        } else if (!isApplicableInCurrentEnvironment(definition)) {
            reason = "not_client_environment";
            detail = "Client-only module on a dedicated server.";
        } else if (requested && !loadedIncompatible.isEmpty()) {
            reason = "incompatible_mod";
            detail = "Disabled because incompatible mod(s) are loaded: "
                    + String.join(", ", loadedIncompatible) + ".";
        } else if (!requested) {
            if (definition.enabledByDefault()) {
                reason = "disabled_by_config";
                detail = "Requested false in ultima.properties; the module default is true.";
            } else {
                reason = "disabled_by_default";
                detail = "Module is opt-in and was not requested.";
            }
        } else if (blockingDependency != null) {
            reason = "dependency_disabled";
            detail = "Required dependency '" + blockingDependency + "' is not enabled.";
        } else {
            reason = "dependency_cycle";
            detail = "Module was requested but remains inactive due to a dependency cycle.";
        }

        return new ResolvedModule(
                module,
                requested,
                enabled,
                definition.enabledByDefault(),
                definition.clientOnly(),
                reason,
                detail,
                definition.dependencies(),
                definition.incompatibleMods(),
                loadedIncompatible,
                blockingDependency,
                UltimaModules.kindKey(definition));
    }

    public String describe(final String module) {
        ResolvedModule resolved = this.resolve(module);
        return resolved.reason() + ": " + resolved.detail();
    }

    public void logResolvedModules() {
        for (ResolvedModule module : this.resolvedModules()) {
            LOGGER.info(
                    "Ultima module {} requested={} enabled={} reason={} ({})",
                    module.key(),
                    module.requested(),
                    module.enabled(),
                    module.reason(),
                    module.detail());
        }
    }

    public record ResolvedModule(
            String key,
            boolean requested,
            boolean enabled,
            boolean enabledByDefault,
            boolean clientOnly,
            String reason,
            String detail,
            List<String> dependencies,
            List<String> incompatibleMods,
            List<String> loadedIncompatibleMods,
            @org.jspecify.annotations.Nullable String blockingDependency,
            String moduleClass) {
        public ResolvedModule {
            dependencies = List.copyOf(dependencies);
            incompatibleMods = List.copyOf(incompatibleMods);
            loadedIncompatibleMods = List.copyOf(loadedIncompatibleMods);
            moduleClass = moduleClass == null ? "unknown" : moduleClass;
        }
    }

    private static UltimaConfig load() {
        Map<String, Boolean> modules = new LinkedHashMap<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            modules.put(module.key(), module.enabledByDefault());
        }

        Path path;
        try {
            path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable t) {
            LOGGER.warn("Could not resolve the Ultima config directory; using defaults.", t);
            return new UltimaConfig(modules);
        }

        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                LOGGER.warn("Could not read {}; using defaults.", path, e);
            }

            for (String key : modules.keySet()) {
                String value = properties.getProperty(key);
                if (value != null) {
                    Boolean parsed = parseBoolean(value);
                    if (parsed == null) {
                        LOGGER.warn("Ignoring invalid boolean value '{}' for '{}' in {}; using {}.",
                                value, key, path, modules.get(key));
                    } else {
                        modules.put(key, parsed);
                    }
                }
            }
        }

        FsrSettings fsrSettings = FsrSettings.fromProperties(properties);
        String presetValue = properties.getProperty(FsrSettings.PRESET_KEY);
        if (presetValue != null && !presetValue.isBlank()
                && FsrQualityPreset.fromKey(presetValue) == FsrQualityPreset.defaultPreset()
                && !presetValue.trim().equalsIgnoreCase(FsrQualityPreset.defaultPreset().name())
                && !presetValue.trim().equalsIgnoreCase("quality")) {
            LOGGER.warn("Ignoring unknown FSR preset '{}' in {}; using {}.",
                    presetValue, path, FsrQualityPreset.defaultPreset().name().toLowerCase());
        }
        String sharpnessValue = properties.getProperty(FsrSettings.SHARPNESS_KEY);
        if (sharpnessValue != null && !sharpnessValue.isBlank() && FsrSettings.parseFloat(sharpnessValue) == null) {
            LOGGER.warn("Ignoring invalid FSR sharpness '{}' in {}; using {}.",
                    sharpnessValue, path, FsrSettings.DEFAULT_SHARPNESS_STOPS);
        }

        UltimaConfig resolved = new UltimaConfig(modules, fsrSettings);
        for (UltimaModules.Module module : UltimaModules.all()) {
            if (isApplicableInCurrentEnvironment(module)
                    && Boolean.TRUE.equals(modules.get(module.key()))
                    && !resolved.isEnabled(module.key())) {
                LOGGER.warn("'{}' is inactive because of a dependency, cycle, or loaded incompatible mod (dependencies={}, incompatibleMods={}).",
                        module.key(), module.dependencies(), module.incompatibleMods());
            }
        }

        writeIfChanged(path, modules, fsrSettings, properties);
        return resolved;
    }

    private static void writeIfChanged(
            final Path path,
            final Map<String, Boolean> modules,
            final FsrSettings fsrSettings,
            final Properties existing) {
        boolean upToDate = existing.size() == modules.size() + 2;
        if (upToDate) {
            for (Map.Entry<String, Boolean> entry : modules.entrySet()) {
                String value = existing.getProperty(entry.getKey());
                if (value == null || !entry.getValue().equals(parseBoolean(value))) {
                    upToDate = false;
                    break;
                }
            }
            if (upToDate) {
                String presetValue = existing.getProperty(FsrSettings.PRESET_KEY);
                String sharpnessValue = existing.getProperty(FsrSettings.SHARPNESS_KEY);
                Float parsedSharpness = sharpnessValue == null ? null : FsrSettings.parseFloat(sharpnessValue);
                if (presetValue == null
                        || FsrQualityPreset.fromKey(presetValue) != fsrSettings.preset()
                        || parsedSharpness == null
                        || parsedSharpness.floatValue() != fsrSettings.sharpnessStops()) {
                    upToDate = false;
                }
            }
        }

        if (upToDate) {
            return;
        }

        write(path, modules, fsrSettings);
    }

    private static void write(final Path path, final Map<String, Boolean> modules, final FsrSettings fsrSettings) {
        FsrSettings settings = fsrSettings == null ? FsrSettings.defaults() : fsrSettings;
        List<String> lines = new ArrayList<>();
        lines.add("# Ultima optimization modules.");
        lines.add("# Set a module to false to fall back to vanilla behaviour for that optimization only.");
        lines.add("# Missing keys use the documented module default.");
        lines.add("# In-game changes are written here immediately; Mixins still apply at next launch.");
        for (UltimaModules.Module module : UltimaModules.all()) {
            boolean value = modules.getOrDefault(module.key(), module.enabledByDefault());
            lines.add("");
            lines.add("# " + module.description());
            lines.add(module.key() + "=" + value);
        }
        lines.add("");
        lines.add("# FSR1 quality preset when fsr_upscaling=true: ultra_quality, quality, balanced,");
        lines.add("# performance, ultra_performance. Scale factors are 1/1.3, 1/1.5, 1/1.7, 1/2, 1/3.");
        lines.add(FsrSettings.PRESET_KEY + "=" + settings.presetKey());
        lines.add("# RCAS sharpness in stops (0 = maximum sharpening, higher = softer). Default 0.2.");
        lines.add("# Sharpness has no in-game slider in this iteration; edit this key or leave the default.");
        lines.add(FsrSettings.SHARPNESS_KEY + "=" + settings.sharpnessStops());

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException | SecurityException e) {
            LOGGER.warn("Could not write {}; Ultima keeps running with the values it resolved.", path, e);
        }
    }

    private static @org.jspecify.annotations.Nullable Boolean parseBoolean(final String value) {
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
    }

    private static boolean isApplicableInCurrentEnvironment(final UltimaModules.Module module) {
        if (!module.clientOnly()) {
            return true;
        }
        try {
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasLoadedIncompatibility(final UltimaModules.Module module) {
        return !loadedIncompatibleMods(module).isEmpty();
    }

    private static List<String> loadedIncompatibleMods(final UltimaModules.Module module) {
        List<String> loaded = new ArrayList<>();
        if (module.incompatibleMods().isEmpty()) {
            return loaded;
        }
        try {
            for (String modId : module.incompatibleMods()) {
                if (FabricLoader.getInstance().isModLoaded(modId)) {
                    loaded.add(modId);
                }
            }
        } catch (Throwable ignored) {
            return loaded;
        }
        return loaded;
    }
}
