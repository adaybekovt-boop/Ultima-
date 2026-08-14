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

    private static final List<Module> ALL = List.of(
            new Module("entity_section_lookup", false,
                    "Look up entity sections intersecting a box directly instead of scanning every "
                            + "loaded section in the same chunk column strip. Opt-in because this replaces "
                            + "a full query method also targeted by entity optimization mods."),
            new Module("block_collision_shape", false,
                    "Only build the collider's voxel shape when a non-cube block shape actually needs to be "
                            + "intersected with it. Opt-in because deferral changes constructor-time wrapper "
                            + "composition around the shape factory."),
            new Module("collision_shell_skip", false,
                    "Reject the one-block shell around a collision query without reading block states when "
                            + "no section it covers can hold a block whose shape reaches outside its own cube. "
                            + "Opt-in because the palette decision is a snapshot for a lazy iterator.",
                    List.of("cursor_step")),
            new Module("cursor_step", true,
                    "Step the block iteration cursor by carrying an increment instead of dividing a running "
                            + "index by the volume's width and height at every position."),
            Module.client("client_chunk_matrix_reuse", true,
                    "Reuse one immutable model-view matrix snapshot for all chunk-section uniforms in a frame. "
                            + "Automatically disabled when Sodium or Iris is loaded.",
                    List.of("sodium", "iris")),
            Module.client("client_chunk_layer_array_reuse", true,
                    "Reuse one ChunkSectionLayer.values() array during chunk submission preparation. "
                            + "Automatically disabled when Sodium or Iris is loaded.",
                    List.of("sodium", "iris")),
            Module.client("client_chunk_dirty_dedup", true,
                    "Collapse duplicate section-dirty writes from expanded block ranges. "
                            + "Automatically disabled when Sodium or Iris is loaded.",
                    List.of("sodium", "iris")),
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
