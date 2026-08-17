package dev.ultima.config.settings;

/**
 * How a settings change becomes the live Mixin/runtime path.
 *
 * <p>Ultima gates Mixins in {@link dev.ultima.config.UltimaMixinPlugin} at game start.
 * Writing {@code ultima.properties} is always immediate; applying the optimization is not.
 */
public enum ApplyPolicy {
    HOT_RELOAD("Changes apply immediately."),
    REJOIN_WORLD("Changes to this option require rejoining the world."),
    RESTART_GAME("Changes to this option require restarting the game.");

    private final String warning;

    ApplyPolicy(final String warning) {
        this.warning = warning;
    }

    public String warning() {
        return this.warning;
    }
}
