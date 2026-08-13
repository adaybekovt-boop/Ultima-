package dev.ultima.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ultima's optimization modules are individually switchable so that a single incompatible
 * optimization can be disabled without giving up the rest of the mod.
 *
 * <p>The configuration is read once, before any Mixin is applied, and must therefore not touch
 * Minecraft classes.
 */
public final class UltimaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-config");
    private static final String FILE_NAME = "ultima.properties";

    private static volatile UltimaConfig instance;

    private final Map<String, Boolean> modules;

    private UltimaConfig(final Map<String, Boolean> modules) {
        this.modules = modules;
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
     * @return whether the module is enabled; unknown module names are treated as enabled so a
     *         missing config entry can never silently disable an optimization.
     */
    public boolean isEnabled(final String module) {
        Boolean value = this.modules.get(module);
        return value == null || value;
    }

    public int enabledModuleCount() {
        int enabled = 0;
        for (Boolean value : this.modules.values()) {
            if (value) {
                enabled++;
            }
        }

        return enabled;
    }

    public int knownModuleCount() {
        return this.modules.size();
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
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("Could not read {}; using defaults.", path, e);
            }

            for (String key : modules.keySet()) {
                String value = properties.getProperty(key);
                if (value != null) {
                    modules.put(key, Boolean.parseBoolean(value.trim()));
                }
            }
        }

        writeIfChanged(path, modules, properties);
        return new UltimaConfig(modules);
    }

    private static void writeIfChanged(final Path path, final Map<String, Boolean> modules, final Properties existing) {
        List<String> lines = new ArrayList<>();
        lines.add("# Ultima optimization modules.");
        lines.add("# Set a module to false to fall back to vanilla behaviour for that optimization only.");
        lines.add("# Unknown or missing keys are treated as enabled.");
        for (UltimaModules.Module module : UltimaModules.all()) {
            lines.add("");
            lines.add("# " + module.description());
            lines.add(module.key() + "=" + modules.get(module.key()));
        }

        boolean upToDate = existing.size() == modules.size();
        if (upToDate) {
            for (Map.Entry<String, Boolean> entry : modules.entrySet()) {
                String value = existing.getProperty(entry.getKey());
                if (value == null || !Boolean.valueOf(Boolean.parseBoolean(value.trim())).equals(entry.getValue())) {
                    upToDate = false;
                    break;
                }
            }
        }

        if (upToDate) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("Could not write {}; Ultima keeps running with the values it resolved.", path, e);
        }
    }
}
