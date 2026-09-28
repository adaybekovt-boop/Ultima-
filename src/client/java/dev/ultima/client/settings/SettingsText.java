package dev.ultima.client.settings;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.settings.ApplyPolicy;
import dev.ultima.config.settings.ModuleDisableMessages;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.SettingsRowView;
import dev.ultima.fsr.FsrQualityPreset;
import dev.ultima.fsr.FsrSettings;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Translated text for the settings screen. English lives in {@code en_us.json} and, for the
 * server-side command output, in the common settings classes; {@code LocalizationChecks} keeps
 * the two in step, so every key built here exists in every shipped language file.
 */
final class SettingsText {
    private static final String STATUS = "ultima.status.";

    private SettingsText() {
    }

    static Component category(final SettingsCategory category) {
        return Component.translatable("ultima.category." + category.key());
    }

    static Component moduleName(final String moduleKey) {
        return Component.translatable("ultima.module." + moduleKey + ".name");
    }

    /**
     * Description, restart policy, current status and pending-restart notice as one multi-line
     * tooltip. {@code diagnostics} is untranslated technical output and may be {@code null}.
     */
    static Component moduleTooltip(
            final SettingsRowView row, final UltimaConfig config, final @Nullable String diagnostics) {
        MutableComponent tooltip = Component.empty();
        tooltip.append(Component.translatable("ultima.module." + row.key() + ".tooltip"));
        tooltip.append("\n");
        tooltip.append(Component.translatable("ultima.apply." + row.applyPolicy().key()));
        if (row.locked() || !row.enabled()) {
            tooltip.append("\n");
            tooltip.append(status(config.resolve(row.key())));
        }
        if (row.pendingRestart()) {
            tooltip.append("\n");
            tooltip.append(Component.translatable("ultima.settings.pending_line"));
        }
        if (diagnostics != null && !diagnostics.isBlank()) {
            tooltip.append("\n");
            tooltip.append(diagnostics);
        }
        return tooltip;
    }

    static Component status(final UltimaConfig.ResolvedModule resolved) {
        String reason = resolved.reason();
        if (ModuleDisableMessages.INCOMPATIBLE_MOD_REASON.equals(reason)) {
            return conflict(resolved.loadedIncompatibleMods());
        }
        if (ModuleDisableMessages.DEPENDENCY_DISABLED_REASON.equals(reason)) {
            String dependency = resolved.blockingDependency();
            Component name = dependency == null
                    ? Component.translatable(STATUS + "another_module")
                    : moduleName(dependency);
            return Component.translatable(STATUS + reason, name);
        }
        if (ModuleDisableMessages.staticMessages().containsKey(reason)) {
            return Component.translatable(STATUS + reason);
        }
        return Component.literal(ModuleDisableMessages.playerFacing(resolved));
    }

    private static Component conflict(final List<String> loadedIncompatibleMods) {
        String prefix = STATUS + ModuleDisableMessages.INCOMPATIBLE_MOD_REASON;
        if (loadedIncompatibleMods.isEmpty()) {
            return Component.translatable(prefix + ".none");
        }
        if (loadedIncompatibleMods.size() > 1) {
            return Component.translatable(
                    prefix + ".multiple", ModuleDisableMessages.joinDisplayNames(loadedIncompatibleMods));
        }
        String modId = loadedIncompatibleMods.getFirst();
        if (ModuleDisableMessages.knownConflictMessages().containsKey(modId)) {
            return Component.translatable(prefix + "." + modId);
        }
        return Component.translatable(prefix + ".other", ModuleDisableMessages.displayModName(modId));
    }

    static Component fsrPresetName(final FsrQualityPreset preset) {
        return Component.translatable("ultima.fsr.preset." + preset.name().toLowerCase(Locale.ROOT));
    }

    static Component fsrPresetTooltip() {
        return Component.translatable(
                "ultima.fsr.preset.tooltip",
                FsrSettings.PRESET_KEY,
                Component.translatable("ultima.apply." + ApplyPolicy.RESTART_GAME.key()),
                FsrSettings.DEFAULT_SHARPNESS_STOPS);
    }
}
