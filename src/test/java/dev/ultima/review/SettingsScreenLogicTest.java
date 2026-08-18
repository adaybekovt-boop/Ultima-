package dev.ultima.review;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import dev.ultima.config.settings.ApplyPolicy;
import dev.ultima.config.settings.FsrPresetRowView;
import dev.ultima.config.settings.ModuleDisableMessages;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.SettingsRowView;
import dev.ultima.config.settings.UltimaCompatibilityReport;
import dev.ultima.config.settings.UltimaSettingsCatalog;
import dev.ultima.config.settings.UltimaSettingsController;
import dev.ultima.fsr.FsrCompatibility;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrSettings;
import dev.ultima.temporal.TemporalMode;
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
        testFsrIsIndependentOfTemporalMode();
        testDisableReasons();
        testFsrIrisCanvasDisableReason();
        testLockedConflictRow();
        testTogglePersistsToExistingFile();
        testUnknownToggleRejected();
        testPendingRestartAndDependency();
        testCompatibilityReportUsesResolve();
        testFsrPresetHiddenWhenModuleOff();
        testFsrPresetRoundTrip();
        System.out.println("Settings screen logic checks passed.");
    }

    private static void testCatalogCoversEveryModule() {
        assertTrue(UltimaSettingsCatalog.missingModuleKeys().isEmpty(),
                "catalog must include every UltimaModules key: " + UltimaSettingsCatalog.missingModuleKeys());
        assertTrue(UltimaSettingsCatalog.unknownCatalogKeys().isEmpty(),
                "catalog must not invent modules: " + UltimaSettingsCatalog.unknownCatalogKeys());
        assertTrue(UltimaSettingsCatalog.byKey("fsr_upscaling") != null,
                "fsr_upscaling must appear in the settings catalog");
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").category() == SettingsCategory.RENDERING,
                "fsr_upscaling is a Rendering row");
        assertTrue("FSR upscaling".equals(UltimaSettingsCatalog.require("fsr_upscaling").displayName()),
                "player-facing FSR name");
        assertTrue(UltimaSettingsCatalog.byKey("mesher_fast_path") != null,
                "mesher_fast_path must appear as its own settings row");
        assertTrue("Unit-cube mesher fast path".equals(UltimaSettingsCatalog.require("mesher_fast_path").displayName()),
                "player-facing mesher fast-path name");
        assertTrue(UltimaSettingsCatalog.byKey("blockentity_sleeping") != null,
                "blockentity_sleeping must appear in the settings catalog");
        assertTrue("Hopper block-entity sleeping".equals(
                UltimaSettingsCatalog.require("blockentity_sleeping").displayName()),
                "player-facing hopper sleeping name");
        assertTrue(UltimaSettingsCatalog.byKey("server_metrics") != null,
                "server_metrics must appear in the settings catalog");
        assertTrue("Server tick metrics".equals(UltimaSettingsCatalog.require("server_metrics").displayName()),
                "player-facing server metrics name");
        assertTrue(UltimaSettingsCatalog.byKey("settings_ui") != null,
                "settings_ui must appear in the settings catalog");
        assertTrue("Title-screen settings button".equals(UltimaSettingsCatalog.require("settings_ui").displayName()),
                "player-facing title-screen button name");
        assertEquals((long)UltimaModules.all().size(), UltimaSettingsCatalog.all().size(), "catalog size");
        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(!spec.displayName().equals(spec.key()),
                    spec.key() + " must have a player-facing name");
            assertTrue(spec.tooltip().length() > 20, spec.key() + " needs a tooltip");
        }
    }

    private static void testCategoriesAndApplyPolicies() {
        assertEquals(6L, UltimaSettingsCatalog.inCategory(SettingsCategory.RENDERING).size(), "rendering count");
        assertEquals(7L, UltimaSettingsCatalog.inCategory(SettingsCategory.SIMULATION).size(), "simulation count");
        assertEquals(6L, UltimaSettingsCatalog.inCategory(SettingsCategory.ADVANCED).size(), "advanced count");
        assertTrue(UltimaSettingsCatalog.require("retained_terrain").category() == SettingsCategory.RENDERING,
                "retained terrain is rendering");
        assertTrue(UltimaSettingsCatalog.require("java_mesher").category() == SettingsCategory.RENDERING,
                "java mesher is rendering");
        assertTrue(UltimaSettingsCatalog.require("mesher_fast_path").category() == SettingsCategory.RENDERING,
                "mesher fast path is rendering");
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").category() == SettingsCategory.RENDERING,
                "FSR is rendering");
        assertTrue(UltimaSettingsCatalog.require("cursor_step").category() == SettingsCategory.SIMULATION,
                "cursor step is simulation");
        assertTrue(UltimaSettingsCatalog.require("blockentity_sleeping").category() == SettingsCategory.SIMULATION,
                "hopper sleeping is simulation");
        assertTrue(UltimaSettingsCatalog.require("client_benchmark").category() == SettingsCategory.ADVANCED,
                "benchmark is advanced");
        assertTrue(UltimaSettingsCatalog.require("terrain_metrics").category() == SettingsCategory.ADVANCED,
                "metrics are advanced");
        assertTrue(UltimaSettingsCatalog.require("server_metrics").category() == SettingsCategory.ADVANCED,
                "server metrics are advanced");
        assertTrue(UltimaSettingsCatalog.require("settings_ui").category() == SettingsCategory.ADVANCED,
                "title-screen button is advanced");
        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(spec.applyPolicy() == ApplyPolicy.RESTART_GAME,
                    spec.key() + " Mixins apply at launch, so the UI must warn about a restart");
        }
        assertTrue(
                UltimaSettingsCatalog.require("retained_terrain").tooltip().contains("Rejoining the world is not enough"),
                "retained terrain must not pretend a world rejoin is sufficient");
        assertTrue(
                UltimaSettingsCatalog.require("fsr_upscaling").tooltip().contains("Iris"),
                "FSR tooltip names Iris");
        assertTrue(
                UltimaSettingsCatalog.require("fsr_upscaling").applyPolicy() == ApplyPolicy.RESTART_GAME,
                "FSR uses the same restart policy as other rendering modules");
        assertTrue(
                UltimaSettingsCatalog.require("mesher_fast_path").applyPolicy() == ApplyPolicy.RESTART_GAME,
                "mesher fast path uses the same restart policy");
        assertTrue(
                UltimaSettingsCatalog.require("blockentity_sleeping").applyPolicy() == ApplyPolicy.RESTART_GAME,
                "hopper sleeping uses the same restart policy");
        assertTrue(
                UltimaSettingsCatalog.require("server_metrics").applyPolicy() == ApplyPolicy.RESTART_GAME,
                "server metrics uses the same restart policy");
        assertTrue(
                UltimaSettingsCatalog.require("settings_ui").applyPolicy() == ApplyPolicy.RESTART_GAME,
                "title-screen button uses the same restart policy");
        assertTrue(
                UltimaSettingsCatalog.require("blockentity_sleeping").tooltip().contains("Lithium"),
                "hopper sleeping tooltip names Lithium");
        assertTrue(
                UltimaSettingsCatalog.require("mesher_fast_path").tooltip().contains("weighted"),
                "mesher fast path tooltip mentions weighted unit cubes");
    }

    private static void testFsrIsIndependentOfTemporalMode() {
        assertTrue(!TemporalMode.FSR_QUALITY.isSupported(), "TemporalMode.FSR_* stays unsupported");
        assertTrue(!TemporalMode.FSR_BALANCED.isSupported(), "TemporalMode.FSR_BALANCED stays unsupported");
        assertTrue(!TemporalMode.FSR_PERFORMANCE.isSupported(), "TemporalMode.FSR_PERFORMANCE stays unsupported");
        assertTrue(!TemporalMode.DLSS_QUALITY.isSupported(), "TemporalMode.DLSS_* stays unsupported");
        assertTrue(TemporalMode.NATIVE.isSupported(), "Native passthrough remains the only TemporalMode");
        for (TemporalMode mode : TemporalMode.values()) {
            assertTrue(UltimaSettingsCatalog.byKey(mode.name()) == null,
                    "catalog must not add TemporalMode." + mode.name() + " as a row");
            assertTrue(UltimaSettingsCatalog.byKey(mode.displayName()) == null,
                    "catalog must not add TemporalMode display name " + mode.displayName());
        }
        assertTrue(UltimaModules.byKey("fsr_upscaling") != null, "FSR is an UltimaModules key");
        assertTrue(
                UltimaSettingsCatalog.require("fsr_upscaling").tooltip().contains("TemporalMode"),
                "FSR tooltip states it is not TemporalMode");
        assertTrue(
                UltimaSettingsCatalog.require("temporal").tooltip().contains("FSR upscaling"),
                "temporal row points players at the separate FSR module");
        UltimaConfig config = defaults();
        SettingsRowView fsr = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(
                fsr.statusReason().equals(config.resolve("fsr_upscaling").reason()),
                "FSR row disable reason is UltimaConfig.resolve(), not TemporalMode");
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

    private static void testFsrIrisCanvasDisableReason() {
        assertTrue(
                UltimaModules.byKey("fsr_upscaling").incompatibleMods().equals(FsrCompatibility.disablingModIds()),
                "module incompatibleMods must be FsrCompatibility.disablingModIds()");
        assertTrue(
                FsrCompatibility.disablingModIds().equals(List.of("iris", "canvas")),
                "Iris then Canvas is the FSR disable list");
        assertTrue(
                !UltimaModules.byKey("fsr_upscaling").incompatibleMods().contains("sodium"),
                "Sodium is not an FSR hard-disable");

        UltimaConfig.ResolvedModule iris = new UltimaConfig.ResolvedModule(
                "fsr_upscaling",
                true,
                false,
                false,
                true,
                "incompatible_mod",
                "Disabled because incompatible mod(s) are loaded: iris.",
                List.of(),
                FsrCompatibility.disablingModIds(),
                List.of("iris"),
                null,
                "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(iris), "Iris incompatible_mod is a hard lock");
        assertTrue(
                ModuleDisableMessages.playerFacing(iris)
                        .equals("Disabled: Iris detected — this module conflicts with Iris shaders"),
                "FSR Iris copy matches other rendering modules");

        UltimaConfig.ResolvedModule canvas = new UltimaConfig.ResolvedModule(
                "fsr_upscaling",
                true,
                false,
                false,
                true,
                "incompatible_mod",
                "Disabled because incompatible mod(s) are loaded: canvas.",
                List.of(),
                FsrCompatibility.disablingModIds(),
                List.of("canvas"),
                null,
                "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(canvas), "Canvas incompatible_mod is a hard lock");
        assertTrue(
                ModuleDisableMessages.playerFacing(canvas)
                        .equals("Disabled: Canvas detected — this module conflicts with Canvas's renderer"),
                "FSR Canvas copy matches other rendering modules");

        UltimaConfig config = defaults();
        UltimaConfig.ResolvedModule live = config.resolve("fsr_upscaling");
        SettingsRowView row = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(row.statusReason().equals(live.reason()), "FSR SettingsRowView.reason == resolve()");
        assertTrue(row.statusDetail().equals(live.detail()), "FSR SettingsRowView.detail == resolve()");
        assertTrue(
                "disabled_by_default".equals(live.reason()) || "not_client_environment".equals(live.reason()),
                "default FSR reason comes from resolve(), not " + live.reason());
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

        SettingsRowView fsr = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(!fsr.displayOn(), "FSR stays default-off");
        assertTrue(fsr.fullTooltip().contains("require restarting the game"), "FSR tooltip warns about restart");
        if (fsr.locked()) {
            assertTrue("not_client_environment".equals(fsr.statusReason()),
                    "headless FSR lock is still resolve() not_client_environment");
        }

        SettingsRowView hopper = SettingsRowView.from(UltimaSettingsCatalog.require("blockentity_sleeping"), config);
        assertTrue(!hopper.displayOn(), "hopper sleeping stays default-off");
        assertTrue(!hopper.locked(), "hopper sleeping is not client-only, so it is not environment-locked");
        assertTrue(hopper.fullTooltip().contains("require restarting the game"), "hopper tooltip warns about restart");
        assertTrue(hopper.fullTooltip().contains("Lithium"), "hopper tooltip still names Lithium");

        SettingsRowView serverMetrics = SettingsRowView.from(UltimaSettingsCatalog.require("server_metrics"), config);
        assertTrue(serverMetrics.displayOn(), "server metrics stay default-on");
        assertTrue(!serverMetrics.locked(), "server metrics are not client-only");
        assertTrue(serverMetrics.fullTooltip().contains("require restarting the game"), "server metrics warn about restart");

        SettingsRowView settingsUi = SettingsRowView.from(UltimaSettingsCatalog.require("settings_ui"), config);
        assertTrue(settingsUi.fullTooltip().contains("require restarting the game"), "title-screen button warns about restart");
        if (settingsUi.locked()) {
            assertTrue("not_client_environment".equals(settingsUi.statusReason()),
                    "headless JavaExec is not a client, so settings_ui locks via resolve()");
            assertTrue(!settingsUi.displayOn(), "locked client-only default-on row displays off on a dedicated/headless process");
        } else {
            assertTrue(settingsUi.displayOn(), "title-screen button stays default-on on a client");
        }
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
            assertTrue("false".equals(properties.getProperty("fsr_upscaling")), "FSR default stays false");
            assertTrue("false".equals(properties.getProperty("mesher_fast_path")), "mesher fast path default stays false");
            assertTrue("false".equals(properties.getProperty("blockentity_sleeping")), "hopper sleeping default stays false");
            assertTrue("true".equals(properties.getProperty("server_metrics")), "server metrics default stays true");
            assertTrue(properties.getProperty(FsrSettings.PRESET_KEY) != null, "FSR preset key is written");
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
        assertTrue(UltimaSettingsCatalog.byKey("fsr_upscaling") != null, "FSR is a known catalog key");
        assertTrue(!controller.setRequested("not_a_module", false), "unknown keys cannot be written");
        assertTrue(!controller.setRequested("FSR_QUALITY", true), "TemporalMode names cannot be written");
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
        assertTrue(json.contains("\"key\": \"fsr_upscaling\""), "report lists FSR");
        assertTrue(json.contains("\"key\": \"mesher_fast_path\""), "report lists mesher fast path");
        assertTrue(json.contains("\"key\": \"server_metrics\""), "report lists server metrics");
        assertTrue(json.contains("\"key\": \"blockentity_sleeping\""), "report lists hopper sleeping");
        assertTrue(json.contains("\"key\": \"settings_ui\""), "report lists title-screen button");
        assertTrue(json.contains("\"reason\":"), "report includes resolve() reason");
        assertTrue(json.contains("\"loadedIncompatibleMods\""), "report includes loaded incompat list");
        assertTrue(json.contains("\"playerFacing\""), "report includes UI copy");
        String chat = UltimaCompatibilityReport.toChatLines(config);
        assertTrue(chat.contains("entity_section_lookup"), "chat listing includes keys");
        assertTrue(chat.contains("fsr_upscaling"), "chat listing includes FSR");
        UltimaConfig.ResolvedModule resolved = config.resolve("entity_section_lookup");
        assertTrue(json.contains(resolved.reason()), "JSON reason matches resolve()");
        assertTrue(json.contains(config.resolve("fsr_upscaling").reason()), "FSR JSON reason matches resolve()");
    }

    private static void testFsrPresetHiddenWhenModuleOff() {
        UltimaConfig off = defaults();
        UltimaSettingsController controller = new UltimaSettingsController(off);
        FsrPresetRowView hidden = controller.fsrPresetRow();
        assertTrue(!off.isRequested("fsr_upscaling"), "FSR default request is false");
        assertTrue(!hidden.visible(), "preset control is hidden when FSR is off");
        assertTrue(!hidden.active(), "preset control is inactive when FSR is off");
        assertTrue(!controller.setFsrPreset(FsrQualityPreset.BALANCED), "preset cannot change while FSR is off");
        assertTrue(off.fsrSettings().preset() == FsrQualityPreset.QUALITY, "default preset stays Quality");

        Map<String, Boolean> requested = defaultMap();
        requested.put("fsr_upscaling", true);
        UltimaConfig on = UltimaConfig.createForTests(requested);
        UltimaSettingsController onController = new UltimaSettingsController(on);
        onController.setCategory(SettingsCategory.SIMULATION);
        assertTrue(!onController.fsrPresetRow().visible(), "preset is hidden outside Rendering");
        onController.setCategory(SettingsCategory.RENDERING);
        FsrPresetRowView shown = onController.fsrPresetRow();
        assertTrue(shown.visible(), "preset is visible in Rendering when FSR is requested");
        assertTrue(shown.preset() == FsrQualityPreset.QUALITY, "default visible preset is Quality");
        if (SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), on).locked()) {
            assertTrue(!shown.active(), "preset is inactive when the FSR row is hard-locked");
        }
    }

    private static void testFsrPresetRoundTrip() {
        Map<String, Boolean> requested = defaultMap();
        requested.put("fsr_upscaling", true);
        UltimaConfig config = UltimaConfig.createForTests(requested, FsrSettings.defaults());
        UltimaSettingsController controller = new UltimaSettingsController(config);
        assertTrue(config.isRequested("fsr_upscaling"), "FSR requested for the round-trip");
        assertTrue(controller.setFsrPreset(FsrQualityPreset.BALANCED), "preset can be written while FSR is requested");
        assertTrue(config.fsrSettings().preset() == FsrQualityPreset.BALANCED, "in-memory preset updates");
        assertEqualsFloat(FsrSettings.DEFAULT_SHARPNESS_STOPS, config.fsrSettings().sharpnessStops(),
                "sharpness stays at the default; no UI slider in this iteration");
        assertTrue(controller.setFsrPreset(FsrQualityPreset.ULTRA_PERFORMANCE), "Ultra Performance is selectable");
        assertTrue(config.fsrSettings().preset() == FsrQualityPreset.ULTRA_PERFORMANCE, "in-memory Ultra Performance");

        Path file;
        try {
            file = Files.createTempFile("ultima-fsr-settings", ".properties");
        } catch (Exception e) {
            throw new AssertionError("could not create temp FSR config", e);
        }
        try {
            controller.persistTo(file);
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            assertTrue("true".equals(properties.getProperty("fsr_upscaling")), "saved fsr_upscaling=true");
            assertTrue(
                    "ultra_performance".equals(properties.getProperty(FsrSettings.PRESET_KEY)),
                    "saved fsr_upscaling.preset=" + properties.getProperty(FsrSettings.PRESET_KEY));
            assertTrue(
                    properties.getProperty(FsrSettings.SHARPNESS_KEY) != null,
                    "sharpness key is still written for a later UI");
            FsrSettings parsed = FsrSettings.fromProperties(properties);
            assertTrue(parsed.preset() == FsrQualityPreset.ULTRA_PERFORMANCE, "fromProperties reads Ultra Performance");
            assertEqualsFloat(FsrSettings.DEFAULT_SHARPNESS_STOPS, parsed.sharpnessStops(), "reloaded sharpness default");

            Map<String, Boolean> reloadedModules = new LinkedHashMap<>();
            for (UltimaModules.Module module : UltimaModules.all()) {
                Boolean value = parseBoolean(properties.getProperty(module.key()));
                reloadedModules.put(module.key(), value == null ? module.enabledByDefault() : value);
            }
            UltimaConfig reloaded = UltimaConfig.createForTests(reloadedModules, parsed);
            assertTrue(reloaded.isRequested("fsr_upscaling"), "restarted config still requests FSR");
            assertTrue(
                    reloaded.fsrSettings().preset() == FsrQualityPreset.ULTRA_PERFORMANCE,
                    "restarted config keeps Ultra Performance");
            UltimaSettingsController restarted = new UltimaSettingsController(reloaded);
            assertTrue(
                    restarted.fsrPresetRow().preset() == FsrQualityPreset.ULTRA_PERFORMANCE,
                    "settings row shows the reloaded preset");
        } catch (Exception e) {
            throw new AssertionError("could not persist or reread FSR preset", e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
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

    private static @org.jspecify.annotations.Nullable Boolean parseBoolean(final String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
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

    private static void assertEqualsFloat(final float expected, final float actual, final String message) {
        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
