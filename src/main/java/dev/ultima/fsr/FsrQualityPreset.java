package dev.ultima.fsr;

import java.util.Locale;

/**
 * Official FSR1 quality presets. The scale factor is {@code 1 / displayScale}
 * on each axis, matching AMD's "Ultra Quality 1.3x / Quality 1.5x / Balanced
 * 1.7x / Performance 2.0x" table. Ultra Performance (3.0x) is included as the
 * optional lowest-quality step from the task table.
 */
public enum FsrQualityPreset {
    ULTRA_QUALITY(1.3),
    QUALITY(1.5),
    BALANCED(1.7),
    PERFORMANCE(2.0),
    ULTRA_PERFORMANCE(3.0);

    private final double displayScale;

    FsrQualityPreset(final double displayScale) {
        this.displayScale = displayScale;
    }

    /**
     * @return AMD display scale (output / input), e.g. 1.5 for Quality
     */
    public double displayScale() {
        return this.displayScale;
    }

    /**
     * @return internal / native scale on each axis ({@code 1 / displayScale})
     */
    public double scaleFactor() {
        return 1.0 / this.displayScale;
    }

    /**
     * @return player-facing label for the settings {@code CycleButton}
     */
    public String displayName() {
        return switch (this) {
            case ULTRA_QUALITY -> "Ultra Quality";
            case QUALITY -> "Quality";
            case BALANCED -> "Balanced";
            case PERFORMANCE -> "Performance";
            case ULTRA_PERFORMANCE -> "Ultra Performance";
        };
    }

    public static FsrQualityPreset defaultPreset() {
        return QUALITY;
    }

    public static FsrQualityPreset fromKey(final String key) {
        if (key == null || key.isBlank()) {
            return defaultPreset();
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (FsrQualityPreset preset : values()) {
            if (preset.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || preset.name().toLowerCase(Locale.ROOT).replace("_", "").equals(normalized.replace("_", ""))) {
                return preset;
            }
        }
        return defaultPreset();
    }
}
