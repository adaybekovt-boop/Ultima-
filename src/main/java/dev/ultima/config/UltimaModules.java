package dev.ultima.config;

import dev.ultima.fsr.FsrCompatibility;
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
    private static final List<String> FSR_RENDERER_FAMILY = FsrCompatibility.disablingModIds();

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
            new Module("server_metrics", true,
                    "Cheap always-on server subsystem timers and counters, plus opt-in /ultima profile tracing. "
                            + "Does not change gameplay. Used to decide what to optimize next, not an optimization. "
                            + "Expected cost: two nanoTime calls and one atomic add per instrumented phase, no "
                            + "allocations on the always-on path."),
            new Module("blockentity_sleeping", false,
                    "Event-driven HopperBlockEntity sleeping: skip tryMoveItems when every vanilla mutation "
                            + "has a synchronous wake channel. Proof-of-correctness prototype, default off. "
                            + "Automatically disabled when Lithium or a Lithium fork is loaded because they "
                            + "replace the same hopper tick.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("recipe_match_cache", false,
                    "Opt-in first-match cache for crafting, furnace/blast/smoker, and brewing lookups. "
                            + "Stores the RecipeHolder (or brewing mix) vanilla's ordered scan would return first "
                            + "for an identical input. Full invalidation on recipe reload. Special/impure recipes "
                            + "fall back to vanilla. Lithium is not auto-disabled: it has no recipe-lookup cache "
                            + "(only furnace/brewing block-entity sleeping). Default off."),
            new Module("tag_bitsets", false,
                    "After tag bind/reload, answer Holder.is(TagKey) with a compact raw-id bitset. Unknown "
                            + "tags and out-of-range ids fall back to vanilla contains(). Default off. "
                            + "Automatically disabled when Lithium or a Lithium fork is loaded because Lithium "
                            + "caches overlapping tag-derived BlockState flags and dual HEAD-cancel Mixins on "
                            + "the same membership/pathing methods are unsafe.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("state_property_cache", false,
                    "Lazy memo of proven-pure BlockState/FluidState properties (PathType, isSignalSource, "
                            + "hasAnalogOutputSignal boolean, static-shape isRedstoneConductor, isPathfindable, "
                            + "fluid source/amount/height). Modded classes and Fabric LandPathTypeRegistry "
                            + "providers are never cached. Default off. Automatically disabled when Lithium or "
                            + "a Lithium fork is loaded because Lithium PathNodeCache / BlockStateFlags occupy "
                            + "the same methods.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("container_slot_mask", false,
                    "Conservative non-empty slot mask for vanilla containers (chests, hoppers, furnaces, "
                            + "brewing stands, comparators). Hint is verified against contents; unlisted or "
                            + "modded inventories fall back to vanilla. Automatically disabled when Lithium "
                            + "or a Lithium fork is loaded because Lithium tracks the same occupancy.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
            new Module("entity_query_early_out", false,
                    "Empty-only early-out for entity section queries (projectiles, fishing hooks, area "
                            + "effect clouds, item/XP attraction, AI broad-phase). Reuses EntitySectionStorage "
                            + "and SectionRangeMath from entity_section_lookup; never filters a non-empty "
                            + "result. Automatically disabled when Lithium or a Lithium fork is loaded.",
                    List.of(),
                    LITHIUM_FAMILY,
                    false),
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
                            + "Automatically disabled when Sodium, Iris, or Canvas is loaded.",
                    RENDERER_FAMILY),
            Module.client("mesher_fast_path", false,
                    "Experimental hybrid mesher: unit-cube fast path from cached vanilla quads plus neighbor "
                            + "occlusion masks, vanilla ModelBlockRenderer/FluidRenderer fallback otherwise. "
                            + "Default off. Independent of retained_terrain and java_mesher; when both java_mesher "
                            + "and mesher_fast_path are requested, mesher_fast_path wins. Automatically disabled "
                            + "when Sodium, Iris, or Canvas is loaded.",
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
                    RENDERER_FAMILY),
            Module.client("fsr_upscaling", false,
                    "Optional FSR1 spatial upscaling (EASU + RCAS). Renders the world at an internal resolution "
                            + "and upscales to native before HUD/GUI. Default off. Isolated from retained_terrain "
                            + "and mesher modules. Automatically disabled when Sodium, Iris, or Canvas is loaded "
                            + "because those renderer integrations own or replace parts of the render pipeline.",
                    FSR_RENDERER_FAMILY),
            Module.client("settings_ui", true,
                    "Title-screen Ultima settings button when Mod Menu is not installed. Client UI only; "
                            + "does not change networking or world simulation. Disable to hide the button; "
                            + "/ultima config and Mod Menu remain available.",
                    List.of()));

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
        return "client_benchmark".equals(key)
                || "terrain_metrics".equals(key)
                || "server_metrics".equals(key);
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
