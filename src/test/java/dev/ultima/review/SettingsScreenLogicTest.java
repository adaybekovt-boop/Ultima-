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

/** Headless checks for the merged settings catalog, disable reasons, and persistence. */
public final class SettingsScreenLogicTest {
    private SettingsScreenLogicTest() {
    }

    public static void run() {
        testCatalogCoversEveryModule();
        testCategoriesAndApplyPolicies();
        testFsrIsIndependentOfTemporalMode();
        testDisableReasons();
        testFsrRendererDisableReason();
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
        assertEquals((long) UltimaModules.all().size(), UltimaSettingsCatalog.all().size(), "catalog size");
        assertEquals(24L, UltimaModules.all().size(), "merged module count");

        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").category() == SettingsCategory.RENDERING,
                "fsr_upscaling is Rendering");
        assertTrue(UltimaSettingsCatalog.require("mesher_fast_path").category() == SettingsCategory.RENDERING,
                "mesher_fast_path is a distinct Rendering row");
        for (String key : List.of(
                "blockentity_sleeping",
                "recipe_match_cache",
                "tag_bitsets",
                "state_property_cache",
                "container_slot_mask",
                "entity_query_early_out")) {
            assertTrue(UltimaSettingsCatalog.require(key).category() == SettingsCategory.SIMULATION,
                    key + " is a Simulation row");
        }
        assertTrue(UltimaSettingsCatalog.require("server_metrics").category() == SettingsCategory.ADVANCED,
                "server_metrics is Advanced instrumentation");
        assertTrue(UltimaSettingsCatalog.require("settings_ui").category() == SettingsCategory.ADVANCED,
                "settings_ui is Advanced client UI");

        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(!spec.displayName().equals(spec.key()), spec.key() + " must have a player-facing name");
            assertTrue(spec.tooltip().length() > 20, spec.key() + " needs a tooltip");
        }
    }

    private static void testCategoriesAndApplyPolicies() {
        assertEquals(6L, UltimaSettingsCatalog.inCategory(SettingsCategory.RENDERING).size(), "rendering count");
        assertEquals(12L, UltimaSettingsCatalog.inCategory(SettingsCategory.SIMULATION).size(), "simulation count");
        assertEquals(6L, UltimaSettingsCatalog.inCategory(SettingsCategory.ADVANCED).size(), "advanced count");
        for (var spec : UltimaSettingsCatalog.all()) {
            assertTrue(spec.applyPolicy() == ApplyPolicy.RESTART_GAME,
                    spec.key() + " Mixins apply at launch, so the UI must warn about a restart");
        }
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").tooltip().contains("Sodium"),
                "FSR tooltip names Sodium");
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").tooltip().contains("Iris"),
                "FSR tooltip names Iris");
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").tooltip().contains("Canvas"),
                "FSR tooltip names Canvas");
    }

    private static void testFsrIsIndependentOfTemporalMode() {
        assertTrue(!TemporalMode.FSR_QUALITY.isSupported(), "TemporalMode.FSR_* stays unsupported");
        assertTrue(!TemporalMode.FSR_BALANCED.isSupported(), "TemporalMode.FSR_BALANCED stays unsupported");
        assertTrue(!TemporalMode.FSR_PERFORMANCE.isSupported(), "TemporalMode.FSR_PERFORMANCE stays unsupported");
        assertTrue(!TemporalMode.DLSS_QUALITY.isSupported(), "TemporalMode.DLSS_* stays unsupported");
        assertTrue(TemporalMode.NATIVE.isSupported(), "Native passthrough remains supported");
        for (TemporalMode mode : TemporalMode.values()) {
            assertTrue(UltimaSettingsCatalog.byKey(mode.name()) == null,
                    "catalog must not add TemporalMode." + mode.name() + " as a row");
            assertTrue(UltimaSettingsCatalog.byKey(mode.displayName()) == null,
                    "catalog must not add TemporalMode display name " + mode.displayName());
        }
        UltimaConfig config = defaults();
        SettingsRowView fsr = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(fsr.statusReason().equals(config.resolve("fsr_upscaling").reason()),
                "FSR row disable reason comes from UltimaConfig.resolve()");
    }

    private static void testDisableReasons() {
        assertTrue(ModuleDisableMessages.conflict(List.of("sodium"))
                        .equals("Disabled: Sodium detected — this module conflicts with Sodium's renderer"),
                "Sodium lock copy");
        assertTrue(ModuleDisableMessages.conflict(List.of("lithium")).contains("Lithium detected"),
                "Lithium lock copy");
        assertTrue(ModuleDisableMessages.conflict(List.of("iris")).contains("Iris shaders"),
                "Iris lock copy");

        UltimaConfig.ResolvedModule locked = new UltimaConfig.ResolvedModule(
                "retained_terrain", true, false, false, true,
                "incompatible_mod",
                "Disabled because incompatible mod(s) are loaded: sodium.",
                List.of(), List.of("sodium", "iris", "canvas"), List.of("sodium"),
                null, "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(locked), "incompatible_mod is a hard lock");
        assertTrue(ModuleDisableMessages.playerFacing(locked)
                        .equals("Disabled: Sodium detected — this module conflicts with Sodium's renderer"),
                "player-facing lock uses loadedIncompatibleMods");

        UltimaConfig.ResolvedModule serverOnly = new UltimaConfig.ResolvedModule(
                "retained_terrain", true, false, false, true,
                "not_client_environment", "Client-only module on a dedicated server.",
                List.of(), List.of(), List.of(), null, "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(serverOnly), "dedicated-server client module is locked");
    }

    private static void testFsrRendererDisableReason() {
        List<String> expected = List.of("canvas");
        assertTrue(FsrCompatibility.disablingModIds().equals(expected),
                "Canvas is the only unconditional FSR incompatible mod");
        assertTrue(UltimaModules.byKey("fsr_upscaling").incompatibleMods().equals(expected),
                "module incompatibleMods matches FsrCompatibility");

        UltimaConfig.ResolvedModule canvas = new UltimaConfig.ResolvedModule(
                "fsr_upscaling", true, false, false, true,
                "incompatible_mod",
                "Disabled because incompatible mod(s) are loaded: canvas.",
                List.of(), expected, List.of("canvas"), null, "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(canvas), "Canvas is an FSR hard lock");
        assertTrue(ModuleDisableMessages.playerFacing(canvas).startsWith("Disabled:"),
                "Canvas has player-facing disable copy");

        UltimaConfig.ResolvedModule irisHook = new UltimaConfig.ResolvedModule(
                "fsr_upscaling", true, false, false, true,
                FsrCompatibility.REASON_NO_SAFE_POST_IRIS_HOOK,
                FsrCompatibility.DETAIL_NO_SAFE_POST_IRIS_HOOK,
                List.of(), expected, List.of(), null, "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(irisHook), "missing Iris hook is a hard lock");
        assertTrue(ModuleDisableMessages.playerFacing(irisHook).contains("no safe post-Iris integration point"),
                "Iris hook reason has distinct player-facing copy");

        UltimaConfig.ResolvedModule irisScale = new UltimaConfig.ResolvedModule(
                "fsr_upscaling", true, false, false, true,
                FsrCompatibility.REASON_IRIS_RESOLUTION_NOT_CONTROLLABLE,
                FsrCompatibility.DETAIL_IRIS_RESOLUTION_NOT_CONTROLLABLE,
                List.of(), expected, List.of(), null, "opt_in_experiment");
        assertTrue(ModuleDisableMessages.isHardLock(irisScale), "Iris resolution limit is a hard lock");
        assertTrue(ModuleDisableMessages.playerFacing(irisScale).contains("internal resolution"),
                "Iris resolution reason has distinct player-facing copy");

        UltimaConfig config = defaults();
        UltimaConfig.ResolvedModule live = config.resolve("fsr_upscaling");
        SettingsRowView row = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(row.statusReason().equals(live.reason()), "FSR SettingsRowView.reason == resolve()");
        assertTrue(row.statusDetail().equals(live.detail()), "FSR SettingsRowView.detail == resolve()");
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
                    "headless client modules lock via resolve()");
            assertTrue(client.lockReason().contains("client-only"), "lock copy names the environment");
        }

        SettingsRowView fsr = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        assertTrue(!fsr.displayOn(), "FSR stays default-off");
        assertTrue(fsr.fullTooltip().contains("require restarting the game"), "FSR tooltip warns about restart");
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
            assertTrue("false".equals(properties.getProperty("recipe_match_cache")), "recipe cache default stays false");
            assertTrue("false".equals(properties.getProperty("container_slot_mask")), "slot mask default stays false");
            assertTrue(properties.getProperty(FsrSettings.PRESET_KEY) != null, "FSR preset key is written");
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
        assertTrue("dependency_disabled".equals(shell.statusReason()), "reason comes from resolve()");
    }

    private static void testCompatibilityReportUsesResolve() {
        UltimaConfig config = defaults();
        String json = UltimaCompatibilityReport.toJson(config);
        for (String key : List.of(
                "entity_section_lookup", "fsr_upscaling", "recipe_match_cache", "tag_bitsets",
                "state_property_cache", "container_slot_mask", "entity_query_early_out")) {
            assertTrue(json.contains("\"key\": \"" + key + "\""), "report lists " + key);
        }
        assertTrue(json.contains("\"reason\":"), "report includes resolve() reason");
        assertTrue(json.contains("\"loadedIncompatibleMods\""), "report includes loaded incompat list");
        assertTrue(json.contains("\"playerFacing\""), "report includes UI copy");
        String chat = UltimaCompatibilityReport.toChatLines(config);
        assertTrue(chat.contains("entity_section_lookup"), "chat listing includes keys");
        assertTrue(chat.contains("fsr_upscaling"), "chat listing includes FSR");
    }

    private static void testFsrPresetHiddenWhenModuleOff() {
        UltimaConfig off = defaults();
        UltimaSettingsController controller = new UltimaSettingsController(off);
        FsrPresetRowView hidden = controller.fsrPresetRow();
        assertTrue(!off.isRequested("fsr_upscaling"), "FSR default request is false");
        assertTrue(!hidden.visible(), "preset control is hidden when FSR is off");
        assertTrue(!hidden.active(), "preset control is inactive when FSR is off");
        assertTrue(!controller.setFsrPreset(FsrQualityPreset.BALANCED), "preset cannot change while FSR is off");

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
        assertTrue(controller.setFsrPreset(FsrQualityPreset.BALANCED), "preset can be written while FSR is requested");
        assertTrue(config.fsrSettings().preset() == FsrQualityPreset.BALANCED, "in-memory preset updates");
        assertTrue(controller.setFsrPreset(FsrQualityPreset.ULTRA_PERFORMANCE), "Ultra Performance is selectable");

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
            assertTrue("ultra_performance".equals(properties.getProperty(FsrSettings.PRESET_KEY)),
                    "saved FSR preset");
            FsrSettings parsed = FsrSettings.fromProperties(properties);
            assertTrue(parsed.preset() == FsrQualityPreset.ULTRA_PERFORMANCE, "fromProperties reads preset");
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
