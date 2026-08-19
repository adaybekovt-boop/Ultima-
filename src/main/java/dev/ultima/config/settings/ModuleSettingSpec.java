package dev.ultima.config.settings;

/**
 * Display metadata for one {@link dev.ultima.config.UltimaModules} key.
 *
 * <p>Does not store on/off state. The live requested/enabled/reason values always come from
 * {@link dev.ultima.config.UltimaConfig#resolve(String)}.
 */
public record ModuleSettingSpec(
        String key,
        String displayName,
        String tooltip,
        SettingsCategory category,
        ApplyPolicy applyPolicy) {
    public ModuleSettingSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("module key is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name is required for " + key);
        }
        if (tooltip == null || tooltip.isBlank()) {
            throw new IllegalArgumentException("tooltip is required for " + key);
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required for " + key);
        }
        if (applyPolicy == null) {
            throw new IllegalArgumentException("apply policy is required for " + key);
        }
    }
}
