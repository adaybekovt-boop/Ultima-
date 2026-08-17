package dev.ultima.temporal;

/**
 * Upscaling / AA mode names for a future settings model. Only {@link #NATIVE} is
 * implemented. Other values exist so the renderer can talk about a mode without
 * pretending it works.
 *
 * <p>Do not add graphics-menu entries for unsupported modes. Spatial FSR1 lives
 * in the separate {@code fsr_upscaling} module and is not a {@code TemporalMode}
 * / {@code TemporalBackend}. The Ultima settings catalog never iterates this
 * enum when building rows.
 */
public enum TemporalMode {
    NATIVE("Native", true),
    DLAA("DLAA", false),
    DLSS_QUALITY("DLSS Quality", false),
    DLSS_BALANCED("DLSS Balanced", false),
    DLSS_PERFORMANCE("DLSS Performance", false),
    FSR_NATIVE_AA("FSR Native AA", false),
    FSR_QUALITY("FSR Quality", false),
    FSR_BALANCED("FSR Balanced", false),
    FSR_PERFORMANCE("FSR Performance", false);

    private final String displayName;
    private final boolean supported;

    TemporalMode(final String displayName, final boolean supported) {
        this.displayName = displayName;
        this.supported = supported;
    }

    public String displayName() {
        return this.displayName;
    }

    /**
     * @return whether a backend actually exists for this mode in this build
     */
    public boolean isSupported() {
        return this.supported;
    }

    public boolean isNative() {
        return this == NATIVE;
    }

    public static TemporalMode fromKey(final String key) {
        if (key == null || key.isBlank()) {
            return NATIVE;
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TemporalMode mode : values()) {
            if (mode.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)
                    || mode.displayName.toLowerCase(java.util.Locale.ROOT).replace(' ', '_').equals(normalized)) {
                return mode;
            }
        }
        return NATIVE;
    }
}
