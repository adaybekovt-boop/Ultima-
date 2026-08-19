package dev.ultima.config.settings;

import java.util.List;

import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrSettings;

/**
 * Sub-control under {@code fsr_upscaling}. Not a {@link ModuleSettingSpec}: the
 * catalog only lists {@link dev.ultima.config.UltimaModules} keys, and this row
 * is derived from that module's requested state plus {@link FsrSettings}.
 *
 * <p>Hidden when FSR is not requested. Shown but inactive when the module row is
 * hard-locked (Iris/Canvas or a dedicated-server environment).
 */
public record FsrPresetRowView(
        boolean visible,
        boolean active,
        FsrQualityPreset preset,
        String displayName,
        String tooltip) {
    public static final String LABEL = "FSR quality";

    public static FsrPresetRowView from(final UltimaConfig config, final SettingsCategory category) {
        SettingsRowView module = SettingsRowView.from(UltimaSettingsCatalog.require("fsr_upscaling"), config);
        boolean visible = category == SettingsCategory.RENDERING && module.requested();
        boolean active = visible && !module.locked();
        FsrQualityPreset preset = config.fsrSettings().preset();
        return new FsrPresetRowView(visible, active, preset, LABEL, tooltipText());
    }

    public static List<FsrQualityPreset> values() {
        return List.of(
                FsrQualityPreset.ULTRA_QUALITY,
                FsrQualityPreset.QUALITY,
                FsrQualityPreset.BALANCED,
                FsrQualityPreset.PERFORMANCE,
                FsrQualityPreset.ULTRA_PERFORMANCE);
    }

    private static String tooltipText() {
        return "AMD FSR1 quality: Ultra Quality 1.3x, Quality 1.5x, Balanced 1.7x, "
                + "Performance 2.0x, Ultra Performance 3.0x. Writes "
                + FsrSettings.PRESET_KEY
                + " in ultima.properties. FSR Mixins still apply after a game restart. "
                + ApplyPolicy.RESTART_GAME.warning()
                + " RCAS sharpness stays at the default "
                + FsrSettings.DEFAULT_SHARPNESS_STOPS
                + " stops; there is no sharpness slider in this menu yet.";
    }
}
