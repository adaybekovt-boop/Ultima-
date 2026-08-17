package dev.ultima.review;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import dev.ultima.config.settings.ApplyPolicy;
import dev.ultima.config.settings.ModuleDisableMessages;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.SettingsRowView;
import dev.ultima.config.settings.UltimaCompatibilityReport;
import dev.ultima.config.settings.UltimaSettingsCatalog;
import dev.ultima.config.settings.UltimaSettingsController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Headless checks for the settings catalog, disable-reason copy, and config persistence.
 * Does not open a Minecraft screen or require a GPU.
 */
public final class SettingsScreenLogicTest {
    private SettingsScreenLogicTest() {
    }

    public static void run() {
        testCatalogCoversEveryModule();
        testCategoriesAndApplyPolicies();
        testDisableReasons();
        testLockedConflictRow();
        testTogglePersistsToExistingFile();
        testUnknownToggleRejected();
        testPendingRestartAndDependency();
        testCompatibilityReportUsesResolve();
        System.out.println("Settings screen logic checks passed.");
    }

    private static void testCatalogCoversEveryModule() {
        assertTrue(UltimaSettingsCatalog.missingModuleKeys().isEmpty(),
                "catalog must include every UltimaModules key: " + UltimaSettingsCatalog.missingModuleKeys());
        assertTrue(UltimaSettingsCatalog.unknownCatalogKeys().isEmpty(),
                "catalog must not invent modules: " + UltimaSettingsCatalog.unknownCatalogKeys());
        assertTrue(UltimaSettingsCatalog.byKey("fsr_upscaling") == null,
                "FSR is not implemented and must not appear as a module");
        assertTrue(UltimaSettingsCatalog.byKey("mesher_fast_path") == null,
                "mesher fast-path is the java_mesher module, not a separate key");
        assertEquals((long)UltimaModules.all().size(), UltimaSettingsCatalog.all().size(), "catalog size");
        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(!spec.displayName().equals(spec.key()),
                    spec.key() + " must have a player-facing name");
            assertTrue(spec.tooltip().length() > 20, spec.key() + " needs a tooltip");
        }
    }

    private static void testCategoriesAndApplyPolicies() {
        assertEquals(4L, UltimaSettingsCatalog.inCategory(SettingsCategory.RENDERING).size(), "rendering count");
        assertEquals(6L, UltimaSettingsCatalog.inCategory(SettingsCategory.SIMULATION).size(), "simulation count");
        assertEquals(4L, UltimaSettingsCatalog.inCategory(SettingsCategory.ADVANCED).size(), "advanced count");
        assertTrue(UltimaSettingsCatalog.require("retained_terrain").category() == SettingsCategory.RENDERING,
                "retained terrain is rendering");
        assertTrue(UltimaSettingsCatalog.require("java_mesher").category() == SettingsCategory.RENDERING,
                "java mesher is rendering");
        assertTrue(UltimaSettingsCatalog.require("cursor_step").category() == SettingsCategory.SIMULATION,
                "cursor step is simulation");
        assertTrue(UltimaSettingsCatalog.require("client_benchmark").category() == SettingsCategory.ADVANCED,
                "benchmark is advanced");
        assertTrue(UltimaSettingsCatalog.require("terrain_metrics").category() == SettingsCategory.ADVANCED,
                "metrics are advanced");
        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(spec.applyPolicy() == ApplyPolicy.RESTART_GAME,
                    spec.key() + " Mixins apply at launch, so the UI must warn about a restart");
        }
        assertTrue(
                UltimaSettingsCatalog.require("retained_terrain").tooltip().contains("Rejoining the world is not enough"),
                "retained terrain must not pretend a world rejoin is sufficient");
    }

    private static void testDisableReasons() {
        assertTrue(
                ModuleDisableMessages.conflict(List.of("sodium"))
                        .equals("Disabled: Sodium detected — this module conflicts with Sodium's renderer"),
                "Sodium lock copy");
        assertTrue(
                ModuleDisableMessages.conflict(List.of("lithium"))
                        .contains("Lithium detected"),
                "Lithium lock copy");
        assertTrue(
                ModuleDisableMessages.conflict(List.of("iris"))
                        .contains("Iris shaders"),
                "Iris lock copy");
        UltimaConfig.ResolvedModule locked = new UltimaConfig.ResolvedModule(
                "retained_terrain",
                true,
                false,
                false,
                true,
                "incompatible_mod",
                "Disabled because incompatible mod(s) are loaded: sodium.",
                List.of(),
                List.of("sodium", "iris", "canvas"),
                List.of("sodium"),
                null,
                "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(locked), "incompatible_mod is a hard lock");
        assertTrue(
                ModuleDisableMessages.playerFacing(locked)
                        .equals("Disabled: Sodium detected — this module conflicts with Sodium's renderer"),
                "player-facing lock uses loadedIncompatibleMods");

        UltimaConfig.ResolvedModule serverOnly = new UltimaConfig.ResolvedModule(
                "retained_terrain",
                true,
                false,
                false,
                true,
                "not_client_environment",
                "Client-only module on a dedicated server.",
                List.of(),
                List.of(),
                List.of(),
                null,
                "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(serverOnly), "dedicated-server client module is locked");
    }

    private static void testLockedConflictRow() {
        UltimaConfig config = defaults();
        SettingsRowView simulation = SettingsRowView.from(UltimaSettingsCatalog.require("cursor_step"), config);
        assertTrue(!simulation.locked(), "common simulation modules are not environment-locked");
        assertTrue(simulation.displayOn(), "cursor_step stays default-on");
        assertTrue(simulation.fullTooltip().contains("require restarting the game"), "restart warning in tooltip");

        SettingsRowView client = SettingsRowView.from(UltimaSettingsCatalog.require("retained_terrain"), config);
        assertTrue(!client.displayOn(), "retained terrain stays default-off");
        if (client.locked()) {
            assertTrue("not_client_environment".equals(client.statusReason()),
                    "headless JavaExec is not a client, so client modules lock via resolve()");
            assertTrue(client.lockReason().contains("client-only"), "lock copy names the environment");
        }
        assertTrue(client.fullTooltip().contains("require restarting the game"), "retained tooltip warns about restart");
    }

    private static void testTogglePersistsToExistingFile() {
        Map<String, Boolean> requested = defaultMap();
        UltimaConfig config = UltimaConfig.createForTests(requested);
        UltimaSettingsController controller = new UltimaSettingsController(config);
        assertTrue(controller.setRequested("cursor_step", false), "cursor_step can be toggled");
        assertTrue(!config.isRequested("cursor_step"), "requested flag updates");
        assertTrue(config.hasPendingRestart("cursor_step"), "launch snapshot still has the old value");
        assertTrue(config.isRequested("entity_section_lookup"), "other defaults are unchanged");

        Path file;
        try {
            file = Files.createTempFile("ultima-settings", ".properties");
        } catch (Exception e) {
            throw new AssertionError("could not create temp config", e);
        }
        try {
            controller.persistTo(file);
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            assertTrue("false".equals(properties.getProperty("cursor_step")), "saved cursor_step=false");
            assertTrue("true".equals(properties.getProperty("entity_section_lookup")), "other keys remain");
            assertTrue("false".equals(properties.getProperty("retained_terrain")), "opt-in default stays false");
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(text.contains("cursor_step=false"), "file uses the existing properties format");
        } catch (Exception e) {
            throw new AssertionError("could not persist or reread ultima.properties", e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }

    private static void testUnknownToggleRejected() {
        UltimaSettingsController controller = new UltimaSettingsController(defaults());
        assertTrue(!controller.setRequested("fsr_upscaling", true), "unknown keys cannot be written");
        assertTrue(!controller.setRequested("not_a_module", false), "unknown keys cannot be written");
    }

    private static void testPendingRestartAndDependency() {
        Map<String, Boolean> requested = defaultMap();
        requested.put("cursor_step", false);
        requested.put("collision_shell_skip", true);
        UltimaConfig config = UltimaConfig.createForTests(requested);
        assertTrue(!config.isEnabled("collision_shell_skip"), "shell skip still requires cursor_step");
        SettingsRowView shell = SettingsRowView.from(UltimaSettingsCatalog.require("collision_shell_skip"), config);
        assertTrue(!shell.locked(), "missing dependency is not a hard lock");
        assertTrue(shell.displayOn(), "requested stays on so the player can see the config value");
        assertTrue(shell.fullTooltip().contains("Optimized block iteration"), "tooltip names the missing dependency");
        assertTrue("dependency_disabled".equals(shell.statusReason()), "reason still comes from resolve()");
    }

    private static void testCompatibilityReportUsesResolve() {
        UltimaConfig config = defaults();
        String json = UltimaCompatibilityReport.toJson(config);
        assertTrue(json.contains("\"key\": \"entity_section_lookup\""), "report lists modules");
        assertTrue(json.contains("\"reason\":"), "report includes resolve() reason");
        assertTrue(json.contains("\"loadedIncompatibleMods\""), "report includes loaded incompat list");
        assertTrue(json.contains("\"playerFacing\""), "report includes UI copy");
        String chat = UltimaCompatibilityReport.toChatLines(config);
        assertTrue(chat.contains("entity_section_lookup"), "chat listing includes keys");
        UltimaConfig.ResolvedModule resolved = config.resolve("entity_section_lookup");
        assertTrue(json.contains(resolved.reason()), "JSON reason matches resolve()");
    }

    private static UltimaConfig defaults() {
        return UltimaConfig.createForTests(defaultMap());
    }

    private static Map<String, Boolean> defaultMap() {
        Map<String, Boolean> modules = new LinkedHashMap<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            modules.put(module.key(), module.enabledByDefault());
        }
        return modules;
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
