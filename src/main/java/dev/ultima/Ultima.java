package dev.ultima;

import dev.ultima.config.UltimaConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Ultima implements ModInitializer {
    public static final String MOD_ID = "ultima";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        UltimaConfig config = UltimaConfig.get();
        LOGGER.info("Ultima initialized with {} of {} optimization modules enabled.",
                config.enabledModuleCount(), config.knownModuleCount());
        warnAboutInertModules(config);
    }

    /**
     * {@code collision_shell_skip} reaches the block iteration cursor through the interface the
     * {@code cursor_step} Mixin adds to it. With {@code cursor_step} off that interface is absent,
     * the shell skip finds nothing to drive, and collision queries silently fall back to the vanilla
     * traversal. That is safe, but the user has disabled an optimization without being told, so say
     * so rather than letting the combination look like it works.
     */
    private static void warnAboutInertModules(final UltimaConfig config) {
        if (config.isEnabled("collision_shell_skip") && !config.isEnabled("cursor_step")) {
            LOGGER.warn("'collision_shell_skip' needs 'cursor_step' to have any effect, and "
                    + "'cursor_step' is disabled. Collision queries will use the vanilla traversal. "
                    + "Enable 'cursor_step', or disable 'collision_shell_skip' as well.");
        }
    }
}
