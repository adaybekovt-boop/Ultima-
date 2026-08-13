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
    public record Module(String key, boolean enabledByDefault, String description) {
    }

    private static final List<Module> ALL = List.of(
            new Module("entity_section_lookup", true,
                    "Look up entity sections intersecting a box directly instead of scanning every "
                            + "loaded section in the same chunk column strip."),
            new Module("block_collision_shape", true,
                    "Only build the collider's voxel shape when a non-cube block shape actually needs to be "
                            + "intersected with it."),
            new Module("collision_fast_path", true,
                    "Skip building intermediate collider lists when a movement cannot collide with anything."),
            new Module("goal_selector", true,
                    "Avoid per-goal flag set iteration while no goal control flag is disabled."),
            new Module("chunk_ticking_range", true,
                    "Cache the per-tick chunk ticking range lookups keyed by chunk position."),
            new Module("random_source", true,
                    "Reuse the per-chunk random tick position work instead of re-deriving shared state."),
            new Module("brightness_cache", true,
                    "Cache the client's per-frame block brightness lookups for the section being rebuilt."),
            new Module("model_part_culling", true,
                    "Skip pose stack work for entity model parts that are neither visible nor have children."));

    private UltimaModules() {
    }

    public static List<Module> all() {
        return ALL;
    }
}
