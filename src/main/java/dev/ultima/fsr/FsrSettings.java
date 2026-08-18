package dev.ultima.fsr;

import java.util.Locale;
import java.util.Properties;

/**
 * Data-only FSR1 settings. The module flag {@code fsr_upscaling} is owned by
 * {@link dev.ultima.config.UltimaConfig}; this object only stores preset and
 * RCAS sharpness.
 */
public final class FsrSettings {
    public static final String PRESET_KEY = "fsr_upscaling.preset";
    public static final String SHARPNESS_KEY = "fsr_upscaling.sharpness";
    public static final String PRESET_PROPERTY = "ultima.fsr.preset";
    public static final String SHARPNESS_PROPERTY = "ultima.fsr.sharpness";
    public static final float DEFAULT_SHARPNESS_STOPS = 0.2F;
    public static final float MIN_SHARPNESS_STOPS = 0.0F;
    public static final float MAX_SHARPNESS_STOPS = 2.0F;

    private final FsrQualityPreset preset;
    private final float sharpnessStops;

    public FsrSettings(final FsrQualityPreset preset, final float sharpnessStops) {
        this.preset = preset == null ? FsrQualityPreset.defaultPreset() : preset;
        this.sharpnessStops = clampSharpness(sharpnessStops);
    }

    public static FsrSettings defaults() {
        return new FsrSettings(FsrQualityPreset.defaultPreset(), DEFAULT_SHARPNESS_STOPS);
    }

    public static FsrSettings fromProperties(final Properties properties) {
        FsrQualityPreset preset = FsrQualityPreset.defaultPreset();
        float sharpness = DEFAULT_SHARPNESS_STOPS;
        if (properties != null) {
            String presetValue = properties.getProperty(PRESET_KEY);
            if (presetValue != null && !presetValue.isBlank()) {
                preset = FsrQualityPreset.fromKey(presetValue);
            }
            String sharpnessValue = properties.getProperty(SHARPNESS_KEY);
            if (sharpnessValue != null && !sharpnessValue.isBlank()) {
                Float parsed = parseFloat(sharpnessValue);
                if (parsed != null) {
                    sharpness = parsed;
                }
            }
        }
        return new FsrSettings(preset, sharpness);
    }

    /**
     * Config file values, then optional {@code -Dultima.fsr.*} overrides.
     */
    public FsrSettings resolved() {
        FsrQualityPreset preset = this.preset;
        float sharpness = this.sharpnessStops;
        String presetOverride = System.getProperty(PRESET_PROPERTY);
        if (presetOverride != null && !presetOverride.isBlank()) {
            preset = FsrQualityPreset.fromKey(presetOverride);
        }
        String sharpnessOverride = System.getProperty(SHARPNESS_PROPERTY);
        if (sharpnessOverride != null && !sharpnessOverride.isBlank()) {
            Float parsed = parseFloat(sharpnessOverride);
            if (parsed != null) {
                sharpness = parsed;
            }
        }
        return new FsrSettings(preset, sharpness);
    }

    public FsrQualityPreset preset() {
        return this.preset;
    }

    public float sharpnessStops() {
        return this.sharpnessStops;
    }

    public static float clampSharpness(final float sharpnessStops) {
        if (Float.isNaN(sharpnessStops) || Float.isInfinite(sharpnessStops)) {
            return DEFAULT_SHARPNESS_STOPS;
        }
        return Math.min(MAX_SHARPNESS_STOPS, Math.max(MIN_SHARPNESS_STOPS, sharpnessStops));
    }

    public static @org.jspecify.annotations.Nullable Float parseFloat(final String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public String presetKey() {
        return this.preset.name().toLowerCase(Locale.ROOT);
    }
}
