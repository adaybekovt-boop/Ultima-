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
}
