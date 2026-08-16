package dev.ultima.config;

import java.util.List;

/**
 * The registry of Ultima optimization modules.
 *
 * <p>The key of a module is also the package segment its Mixins live in, so
 * {@code dev.ultima.mixin.entity_section_lookup.FooMixin} belongs to the
 * {@code entity_section_lookup} module. {@link UltimaMixinPlugin} uses that to skip the Mixins of a
 * disabled module.
 */
public final class UltimaModules {
    public enum Kind {
        SHIPPED_DEFAULT,
        OPT_IN_EXPERIMENT,
        INSTRUMENTATION
    }

    public record Module(
            String key,
            boolean enabledByDefault,
            String description,
            List<String> dependencies,
            List<String> incompatibleMods,
            boolean clientOnly) {
        public Module(final String key, final boolean enabledByDefault, final String description) {
            this(key, enabledByDefault, description, List.of(), List.of(), false);
        }

        public Module(
                final String key,
                final boolean enabledByDefault,
                final String description,
                final List<String> dependencies) {
            this(key, enabledByDefault, description, dependencies, List.of(), false);
        }

        public Module {
            dependencies = List.copyOf(dependencies);
            incompatibleMods = List.copyOf(incompatibleMods);
        }

        public static Module client(
                final String key,
                final boolean enabledByDefault,
                final String description,
                final List<String> incompatibleMods) {
            return new Module(key, enabledByDefault, description, List.of(), incompatibleMods, true);
        }
    }

    private static final List<String> LITHIUM_FAMILY = List.of("lithium", "canary", "radium");
    private static final List<String> RENDERER_FAMILY = List.of("sodium", "iris", "canvas");

    private static final List<Module> ALL = List.of(
            new Module("entity_section_lookup", true,
                    "Look up entity sections intersecting a box directly instead of scanning every "
                            + "loaded section in the same chunk column strip. Automatically disabled when "
                            + "Lithium or a Lithium fork is loaded because they replace the same query.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("block_collision_shape", true,
                    "Only build the collider's voxel shape when a non-cube block shape actually needs to be "
                            + "intersected with it. Automatically disabled when Lithium or a Lithium fork is loaded.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("collision_shell_skip", true,
                    "Reject the one-block shell around a collision query without reading block states when "
                            + "no section it covers can hold a block whose shape reaches outside its own cube. "
                            + "Automatically disabled when Lithium or a Lithium fork is loaded.",
                    List.of("cursor_step"),
                    LITHIUM_FAMILY,
                    false),
            new Module("supporting_block_shape_skip", true,
                    "Skip VoxelShape.move for full-cube blocks in findSupportingBlock, which discards the "
                            + "shape and only keeps the BlockPos. Automatically disabled when Lithium or a "
                            + "Lithium fork is loaded.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("full_cube_move", true,
                    "Replace Shapes.block().move(integer offset) with a compact world-space cube that has "
                            + "the same coordinates and collision as vanilla's allocating ArrayVoxelShape. "
                            + "Automatically disabled when Lithium or a Lithium fork is loaded.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("cursor_step", true,
                    "Step the block iteration cursor by carrying an increment instead of dividing a running "
                            + "index by the volume's width and height at every position."),
            Module.client("client_benchmark", false,
                    "Record reproducible client frame-time distributions when explicitly requested.",
                    List.of()),
            Module.client("terrain_metrics", true,
                    "Record independent terrain prepare/submit CPU, draw counts, and rebuild/upload counters. "
                            + "Does not change rendering. Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("retained_terrain", false,
                    "Experimental retained opaque terrain: section metadata table, persistent command slots, "
                            + "and multi-draw/indirect submission. Vanilla path remains the fallback. "
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("render_snapshot", false,
                    "Share immutable block-entity snapshots across SectionCopy objects of the same chunk "
                            + "inside one extract. Does not share palettes. "
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("java_mesher", false,
                    "Packed x-fastest section compile loop matching BlockPos.betweenClosed, with worker-owned "
                            + "scratch and tessellator reuse. Exact visit order. "
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded. "
                            + "Skipped when mesher_fast_path is enabled.",
                    RENDERER_FAMILY),
            Module.client("mesher_fast_path", false,
                    "Experimental hybrid mesher: unit-cube fast path from cached vanilla quads plus neighbor "
                            + "occlusion masks, vanilla ModelBlockRenderer/FluidRenderer fallback otherwise. "
                            + "Default off. Independent of retained_terrain and of java_mesher; when both "
                            + "java_mesher and mesher_fast_path are requested, mesher_fast_path wins. "
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("section_task_queue", false,
                    "Compact cancelled section compile tasks in one pass and park workers on upload backpressure "
                            + "instead of spinning. Preserves vanilla nearest-task and recompile-quota policy. "
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("rgss_endpoint", false,
                    "Experimental RGSS endpoint specialization. Reject unless GPU frame time improves by at least "
                            + "3% in an RGSS-limited workload. Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("temporal", true,
                    "Backend-neutral temporal frame contract with Native passthrough. Captures current/previous "
                            + "view-projection, depth/color views, and history-reset events. Does not change pixels. "
                            + "DLSS/FSR backends are not implemented. Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY));

    private UltimaModules() {
    }

    public static List<Module> all() {
        return ALL;
    }

    public static @org.jspecify.annotations.Nullable Module byKey(final String key) {
        for (Module module : ALL) {
            if (module.key().equals(key)) {
                return module;
            }
        }
        return null;
    }

    public static boolean isInstrumentation(final String key) {
        return "client_benchmark".equals(key) || "terrain_metrics".equals(key);
    }

    public static boolean isOptInExperiment(final String key) {
        Module module = byKey(key);
        return module != null && kind(module) == Kind.OPT_IN_EXPERIMENT;
    }

    public static Kind kind(final Module module) {
        if (isInstrumentation(module.key())) {
            return Kind.INSTRUMENTATION;
        }
        return module.enabledByDefault() ? Kind.SHIPPED_DEFAULT : Kind.OPT_IN_EXPERIMENT;
    }

    public static String kindKey(final Module module) {
        return switch (kind(module)) {
            case SHIPPED_DEFAULT -> "shipped_default";
            case OPT_IN_EXPERIMENT -> "opt_in_experiment";
            case INSTRUMENTATION -> "instrumentation";
        };
    }
}
