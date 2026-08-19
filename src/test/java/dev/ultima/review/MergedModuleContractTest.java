package dev.ultima.review;

import dev.ultima.config.UltimaModules;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.UltimaSettingsCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Final registry contract for the multi-branch merge. */
final class MergedModuleContractTest {
    private static final List<String> LITHIUM_FAMILY = List.of("lithium", "canary", "radium");
    private static final List<String> RENDERER_FAMILY = List.of("sodium", "iris", "canvas");

    private MergedModuleContractTest() {
    }

    static void run() {
        Map<String, Boolean> expectedDefaults = new LinkedHashMap<>();
        expectedDefaults.put("entity_section_lookup", true);
        expectedDefaults.put("block_collision_shape", true);
        expectedDefaults.put("collision_shell_skip", true);
        expectedDefaults.put("supporting_block_shape_skip", true);
        expectedDefaults.put("full_cube_move", true);
        expectedDefaults.put("cursor_step", true);
        expectedDefaults.put("server_metrics", true);
        expectedDefaults.put("blockentity_sleeping", false);
        expectedDefaults.put("recipe_match_cache", false);
        expectedDefaults.put("tag_bitsets", false);
        expectedDefaults.put("state_property_cache", false);
        expectedDefaults.put("container_slot_mask", false);
        expectedDefaults.put("entity_query_early_out", false);
        expectedDefaults.put("client_benchmark", false);
        expectedDefaults.put("terrain_metrics", true);
        expectedDefaults.put("retained_terrain", false);
        expectedDefaults.put("render_snapshot", false);
        expectedDefaults.put("java_mesher", false);
        expectedDefaults.put("mesher_fast_path", false);
        expectedDefaults.put("section_task_queue", false);
        expectedDefaults.put("rgss_endpoint", false);
        expectedDefaults.put("temporal", true);
        expectedDefaults.put("fsr_upscaling", false);
        expectedDefaults.put("settings_ui", true);

        assertEquals(24, UltimaModules.all().size(), "module count");
        assertEquals(expectedDefaults.size(), UltimaSettingsCatalog.all().size(), "settings catalog count");
        for (Map.Entry<String, Boolean> entry : expectedDefaults.entrySet()) {
            UltimaModules.Module module = UltimaModules.byKey(entry.getKey());
            assertTrue(module != null, "registered module " + entry.getKey());
            assertTrue(module.enabledByDefault() == entry.getValue(),
                    entry.getKey() + " default=" + entry.getValue());
            assertTrue(UltimaSettingsCatalog.byKey(entry.getKey()) != null,
                    entry.getKey() + " has settings UI row");
        }

        for (String key : List.of(
                "entity_section_lookup",
                "block_collision_shape",
                "collision_shell_skip",
                "supporting_block_shape_skip",
                "full_cube_move",
                "blockentity_sleeping",
                "tag_bitsets",
                "state_property_cache",
                "container_slot_mask",
                "entity_query_early_out")) {
            assertEquals(LITHIUM_FAMILY, UltimaModules.byKey(key).incompatibleMods(),
                    key + " Lithium-family auto-disable");
        }
        assertTrue(UltimaModules.byKey("recipe_match_cache").incompatibleMods().isEmpty(),
                "recipe_match_cache deliberately stays compatible with Lithium/Canary/Radium");

        for (String key : List.of("retained_terrain", "mesher_fast_path", "fsr_upscaling")) {
            assertEquals(RENDERER_FAMILY, UltimaModules.byKey(key).incompatibleMods(),
                    key + " Sodium/Iris/Canvas auto-disable");
        }

        for (String key : List.of(
                "blockentity_sleeping", "recipe_match_cache", "tag_bitsets", "state_property_cache",
                "container_slot_mask", "entity_query_early_out")) {
            assertTrue(UltimaSettingsCatalog.require(key).category() == SettingsCategory.SIMULATION,
                    key + " category Simulation");
        }
        assertTrue(UltimaSettingsCatalog.require("mesher_fast_path").category() == SettingsCategory.RENDERING,
                "mesher_fast_path category Rendering");
        assertTrue(UltimaSettingsCatalog.require("fsr_upscaling").category() == SettingsCategory.RENDERING,
                "fsr_upscaling category Rendering");
        assertTrue(UltimaSettingsCatalog.require("server_metrics").category() == SettingsCategory.ADVANCED,
                "server_metrics category Advanced");
        assertTrue(UltimaSettingsCatalog.require("settings_ui").category() == SettingsCategory.ADVANCED,
                "settings_ui category Advanced");

        System.out.println("Merged module contract checks passed: 24 modules, defaults/categories/auto-disable verified.");
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final int expected, final int actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
