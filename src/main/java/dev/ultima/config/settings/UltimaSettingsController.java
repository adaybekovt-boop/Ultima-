package dev.ultima.config.settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.ultima.config.UltimaConfig;
import dev.ultima.fsr.FsrQualityPreset;

/**
 * Screen/command logic over {@link UltimaConfig}. Toggles write the existing
 * {@code ultima.properties} map; they do not invent a second store.
 *
 * <p>FSR quality is a sub-control of {@code fsr_upscaling}, not a TemporalMode
 * entry. Disable reasons still come from {@link UltimaConfig#resolve(String)}.
 */
public final class UltimaSettingsController {
    private final UltimaConfig config;
    private SettingsCategory category = SettingsCategory.RENDERING;

    public UltimaSettingsController(final UltimaConfig config) {
        this.config = config;
    }

    public static UltimaSettingsController live() {
        return new UltimaSettingsController(UltimaConfig.get());
    }

    public UltimaConfig config() {
        return this.config;
    }

    public SettingsCategory category() {
        return this.category;
    }

    public void setCategory(final SettingsCategory category) {
        this.category = category == null ? SettingsCategory.RENDERING : category;
    }

    public List<SettingsRowView> rows() {
        List<SettingsRowView> rows = new ArrayList<>();
        for (ModuleSettingSpec spec : UltimaSettingsCatalog.inCategory(this.category)) {
            rows.add(SettingsRowView.from(spec, this.config));
        }
        return List.copyOf(rows);
    }

    public List<SettingsRowView> allRows() {
        List<SettingsRowView> rows = new ArrayList<>();
        for (ModuleSettingSpec spec : UltimaSettingsCatalog.all()) {
            rows.add(SettingsRowView.from(spec, this.config));
        }
        return List.copyOf(rows);
    }

    public boolean setRequested(final String key, final boolean requested) {
        ModuleSettingSpec spec = UltimaSettingsCatalog.byKey(key);
        if (spec == null) {
            return false;
        }
        SettingsRowView current = SettingsRowView.from(spec, this.config);
        if (current.locked()) {
            return false;
        }
        return this.config.setRequested(key, requested);
    }

    public FsrPresetRowView fsrPresetRow() {
        return FsrPresetRowView.from(this.config, this.category);
    }

    /**
     * Writes {@code fsr_upscaling.preset} when FSR is requested. Allowed even if
     * the module row is environment-locked so a dedicated-server or headless
     * write still round-trips into {@code ultima.properties}.
     */
    public boolean setFsrPreset(final FsrQualityPreset preset) {
        if (preset == null || !this.config.isRequested("fsr_upscaling")) {
            return false;
        }
        this.config.setFsrPreset(preset);
        return true;
    }

    public boolean hasPendingRestart() {
        return this.config.hasPendingRestart();
    }

    public void persist() {
        this.config.save();
    }

    public void persistTo(final Path path) {
        this.config.saveTo(path);
    }
}
