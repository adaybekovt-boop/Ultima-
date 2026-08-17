package dev.ultima.config.settings;

/**
 * Player-facing groups on the Ultima settings screen. Keys stay in
 * {@link dev.ultima.config.UltimaModules}; this is display metadata only.
 */
public enum SettingsCategory {
    RENDERING("Rendering"),
    SIMULATION("Simulation"),
    ADVANCED("Advanced");

    private final String displayName;

    SettingsCategory(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
