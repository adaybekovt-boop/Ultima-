package dev.ultima.config.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.ultima.config.UltimaModules;

/**
 * Human-readable labels, categories, and apply policies for every registered Ultima module.
 *
 * <p>This is the settings-screen source of display text. Enablement and auto-disable reasons still
 * come from {@link dev.ultima.config.UltimaConfig#resolve(String)} — the catalog never consults
 * {@link dev.ultima.temporal.TemporalMode}. Spatial FSR1 is the {@code fsr_upscaling} module
 * (Rendering). {@code TemporalMode.FSR_*} remains unsupported and has no menu entries.
 * {@code java_mesher} is the shipped mesher fast-path; it has no extra sub-options.
 */
public final class UltimaSettingsCatalog {
    private static final Map<String, ModuleSettingSpec> BY_KEY = new LinkedHashMap<>();

    static {
        register(new ModuleSettingSpec(
                "retained_terrain",
                "Retained terrain renderer",
                "Keeps opaque terrain meshes on the GPU and submits them with multi-draw. "
                        + "Vanilla remains the fallback if the path cannot run. GPU command buffers "
                        + "are allocated for the world; toggling this after launch cannot safely "
                        + "create or tear those resources down. Rejoining the world is not enough.",
                SettingsCategory.RENDERING,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "java_mesher",
                "Faster section mesher",
                "Uses a packed section compile loop with worker-owned scratch and tessellator reuse. "
                        + "Visit order matches vanilla BlockPos.betweenClosed. No extra mesher "
                        + "sub-settings exist in this build.",
                SettingsCategory.RENDERING,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "render_snapshot",
                "Shared render snapshots",
                "Shares immutable block-entity snapshots across SectionCopy objects of the same chunk "
                        + "during one extract. Does not share palettes.",
                SettingsCategory.RENDERING,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "section_task_queue",
                "Section compile task queue",
                "Compacts cancelled section compile tasks in one pass and parks workers on upload "
                        + "backpressure instead of spinning. Preserves vanilla nearest-task policy.",
                SettingsCategory.RENDERING,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "fsr_upscaling",
                "FSR upscaling",
                "Optional FSR1 spatial upscaling (EASU + RCAS). Renders the world at an internal "
                        + "resolution and upscales to native before HUD, GUI, and chat. Default off. "
                        + "This is a standalone module, not a TemporalMode / TemporalBackend — "
                        + "TemporalMode.FSR_* stays unsupported. Auto-off when Iris or Canvas is "
                        + "loaded because they own the post-process framebuffer. Sodium without Iris "
                        + "is allowed. A quality preset appears under this toggle when it is on. "
                        + "RCAS sharpness stays at 0.2; there is no sharpness slider in this menu yet.",
                SettingsCategory.RENDERING,
                ApplyPolicy.RESTART_GAME));

        register(new ModuleSettingSpec(
                "entity_section_lookup",
                "Faster entity lookups",
                "Looks up entity sections that intersect a box instead of scanning every loaded "
                        + "section in the same chunk column.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "block_collision_shape",
                "Deferred collision shapes",
                "Only builds the collider voxel shape when a non-cube block actually needs to be "
                        + "intersected with it.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "collision_shell_skip",
                "Collision shell skip",
                "Rejects the one-block shell around a collision query without reading block states "
                        + "when no covered section can hold a shape that leaves its own cube. "
                        + "Requires Optimized block iteration.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "supporting_block_shape_skip",
                "Supporting-block shape skip",
                "Skips VoxelShape.move for full-cube blocks when finding the supporting block, "
                        + "which discards the shape and only keeps the BlockPos.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "full_cube_move",
                "Full-cube collision move",
                "Replaces integer-offset full-cube VoxelShape.move with a compact world-space cube "
                        + "that matches vanilla collision.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "cursor_step",
                "Optimized block iteration",
                "Steps the block iteration cursor by carrying an increment instead of dividing a "
                        + "running index at every position. Required by Collision shell skip.",
                SettingsCategory.SIMULATION,
                ApplyPolicy.RESTART_GAME));

        register(new ModuleSettingSpec(
                "temporal",
                "Temporal frame contract",
                "Captures current and previous view-projection, depth/color views, and history-reset "
                        + "events. Native passthrough only — TemporalMode FSR/DLSS backends are not "
                        + "implemented and have no quality preset on this row. Spatial FSR1 is the "
                        + "separate Rendering option \"FSR upscaling\". Does not change pixels.",
                SettingsCategory.ADVANCED,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "rgss_endpoint",
                "RGSS endpoint specialization",
                "Experimental RGSS endpoint specialization. Keep off unless a measured RGSS-limited "
                        + "workload improves GPU frame time by at least 3%.",
                SettingsCategory.ADVANCED,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "terrain_metrics",
                "Terrain frame metrics",
                "Records terrain prepare/submit CPU, draw counts, and rebuild/upload counters. "
                        + "Does not change rendering.",
                SettingsCategory.ADVANCED,
                ApplyPolicy.RESTART_GAME));
        register(new ModuleSettingSpec(
                "client_benchmark",
                "Client frame benchmark",
                "Records reproducible client frame-time distributions when a benchmark is explicitly "
                        + "requested. Instrumentation only.",
                SettingsCategory.ADVANCED,
                ApplyPolicy.RESTART_GAME));
    }

    private UltimaSettingsCatalog() {
    }

    private static void register(final ModuleSettingSpec spec) {
        if (BY_KEY.put(spec.key(), spec) != null) {
            throw new IllegalStateException("duplicate settings spec for " + spec.key());
        }
    }

    public static List<ModuleSettingSpec> all() {
        return List.copyOf(BY_KEY.values());
    }

    public static List<ModuleSettingSpec> inCategory(final SettingsCategory category) {
        List<ModuleSettingSpec> specs = new ArrayList<>();
        for (ModuleSettingSpec spec : BY_KEY.values()) {
            if (spec.category() == category) {
                specs.add(spec);
            }
        }
        return List.copyOf(specs);
    }

    public static @org.jspecify.annotations.Nullable ModuleSettingSpec byKey(final String key) {
        return BY_KEY.get(key);
    }

    public static ModuleSettingSpec require(final String key) {
        ModuleSettingSpec spec = BY_KEY.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("no settings spec for " + key);
        }
        return spec;
    }

    public static String displayName(final String key) {
        ModuleSettingSpec spec = BY_KEY.get(key);
        return spec == null ? key : spec.displayName();
    }

    /**
     * @return missing module keys that exist in {@link UltimaModules} but not in this catalog
     */
    public static List<String> missingModuleKeys() {
        List<String> missing = new ArrayList<>();
        for (UltimaModules.Module module : UltimaModules.all()) {
            if (!BY_KEY.containsKey(module.key())) {
                missing.add(module.key());
            }
        }
        return missing;
    }

    /**
     * @return catalog keys that do not exist in {@link UltimaModules}
     */
    public static List<String> unknownCatalogKeys() {
        List<String> extra = new ArrayList<>();
        for (String key : BY_KEY.keySet()) {
            if (UltimaModules.byKey(key) == null) {
                extra.add(key);
            }
        }
        return extra;
    }
}
