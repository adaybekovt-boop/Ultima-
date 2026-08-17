package dev.ultima.config.settings;

import java.util.ArrayList;
import java.util.List;

import dev.ultima.config.UltimaConfig;

/**
 * One settings-row snapshot. Built from a catalog spec plus
 * {@link UltimaConfig#resolve(String)} so disable reasons are not duplicated.
 */
public record SettingsRowView(
        String key,
        String displayName,
        String tooltip,
        SettingsCategory category,
        ApplyPolicy applyPolicy,
        boolean requested,
        boolean enabled,
        boolean appliedAtLaunch,
        boolean displayOn,
        boolean locked,
        String lockReason,
        String statusReason,
        String statusDetail,
        String playerFacingStatus,
        boolean pendingRestart) {
    public static SettingsRowView from(final ModuleSettingSpec spec, final UltimaConfig config) {
        UltimaConfig.ResolvedModule resolved = config.resolve(spec.key());
        boolean locked = ModuleDisableMessages.isHardLock(resolved);
        boolean displayOn = locked ? resolved.enabled() : resolved.requested();
        String playerFacing = ModuleDisableMessages.playerFacing(resolved);
        return new SettingsRowView(
                spec.key(),
                spec.displayName(),
                spec.tooltip(),
                spec.category(),
                spec.applyPolicy(),
                resolved.requested(),
                resolved.enabled(),
                config.wasEnabledAtLaunch(spec.key()),
                displayOn,
                locked,
                locked ? playerFacing : "",
                resolved.reason(),
                resolved.detail(),
                playerFacing,
                config.hasPendingRestart(spec.key()));
    }

    public String fullTooltip() {
        List<String> lines = new ArrayList<>();
        lines.add(this.tooltip);
        lines.add(this.applyPolicy.warning());
        if (this.locked) {
            lines.add(this.lockReason);
        } else if (!this.enabled) {
            lines.add(this.playerFacingStatus);
        }
        if (this.pendingRestart) {
            lines.add("Pending: restart the game to apply this change.");
        }
        return String.join("\n", lines);
    }
}
