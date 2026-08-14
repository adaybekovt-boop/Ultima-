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
            new Module("collision_shell_skip", true,
                    "Reject the one-block shell around a collision query without reading block states when "
                            + "no section it covers can hold a block whose shape reaches outside its own cube."),
            new Module("cursor_step", true,
                    "Step the block iteration cursor by carrying an increment instead of dividing a running "
                            + "index by the volume's width and height at every position."));

    private UltimaModules() {
    }

    public static List<Module> all() {
        return ALL;
    }
}
