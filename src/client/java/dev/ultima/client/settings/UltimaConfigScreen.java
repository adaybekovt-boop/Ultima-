package dev.ultima.client.settings;

import dev.ultima.config.settings.FsrPresetRowView;
import dev.ultima.config.settings.SettingsCategory;
import dev.ultima.config.settings.SettingsRowView;
import dev.ultima.config.settings.UltimaSettingsController;
import dev.ultima.fsr.FsrQualityPreset;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * In-game Ultima settings. Vanilla {@link OptionsSubScreen} layout, no Cloth Config.
 *
 * <pre>
 * +----------------------------------------------------------+
 * |                     Ultima Settings                      |
 * |----------------------------------------------------------|
 * | [Rendering] [Simulation] | [Advanced]                    |
 * | Rendering                                                |
 * | Retained terrain renderer                     [ OFF ]    |
 * |   tooltip: description + restart warning + lock reason   |
 * | Faster section mesher                         [ OFF ]    |
 * | FSR upscaling                                 [ OFF ]    |
 * |   FSR quality   [ Quality ]  (only when FSR is on)       |
 * |----------------------------------------------------------|
 * | Restart the game to apply pending module changes         |
 * | Saved to ultima.properties. Mixins apply at next launch. |
 * |                         [ Done ]                         |
 * +----------------------------------------------------------+
 * </pre>
 */
public final class UltimaConfigScreen extends OptionsSubScreen {
    private final UltimaSettingsController controller;
    private @Nullable UltimaSettingsList settingsList;

    public UltimaConfigScreen(final @Nullable Screen parent) {
        super(
                parent,
                Minecraft.getInstance().options,
                Component.translatableWithFallback("ultima.settings.title", "Ultima Settings"));
        this.controller = UltimaSettingsController.live();
    }

    @Override
    protected void addTitle() {
        this.layout.addTitleHeader(this.title, this.font);
    }

    @Override
    protected void addContents() {
        UltimaSettingsList list = new UltimaSettingsList(this.minecraft, this.width, this);
        this.settingsList = list;
        this.list = this.layout.addToContents(list);
        this.populateList(false);
    }

    @Override
    protected void addOptions() {
    }

    @Override
    protected void addFooter() {
        this.layout.setFooterHeight(58);
        LinearLayout footer = LinearLayout.vertical().spacing(2);
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new StringWidget(
                Component.translatableWithFallback(
                                "ultima.settings.persist_hint",
                                "Saved to ultima.properties. Mixins apply after a game restart.")
                        .withStyle(ChatFormatting.GRAY),
                this.font));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
        this.layout.addToFooter(footer);
    }

    @Override
    public void removed() {
        this.controller.persist();
    }

    private void populateList(final boolean preserveScroll) {
        if (this.settingsList == null) {
            return;
        }
        double scroll = this.settingsList.scrollAmount();
        if (preserveScroll) {
            this.settingsList.resetPreservingScroll();
        } else {
            this.settingsList.reset();
        }

        this.settingsList.addSmall(
                this.categoryButton(SettingsCategory.RENDERING),
                this.categoryButton(SettingsCategory.SIMULATION));
        this.settingsList.addSmall(this.categoryButton(SettingsCategory.ADVANCED), null);

        if (this.controller.hasPendingRestart()) {
            this.settingsList.addHeader(Component.translatableWithFallback(
                            "ultima.settings.pending_restart",
                            "Restart the game to apply pending module changes.")
                    .withStyle(ChatFormatting.GOLD));
        }

        this.settingsList.addHeader(Component.literal(this.controller.category().displayName()));
        for (SettingsRowView row : this.controller.rows()) {
            this.settingsList.addBig(this.moduleButton(row));
            if ("fsr_upscaling".equals(row.key())) {
                FsrPresetRowView preset = this.controller.fsrPresetRow();
                if (preset.visible()) {
                    this.settingsList.addBig(this.fsrPresetButton(preset));
                }
            }
        }

        if (preserveScroll) {
            this.settingsList.setScrollAmount(scroll);
        }
    }

    private Button categoryButton(final SettingsCategory category) {
        boolean selected = this.controller.category() == category;
        Button button = Button.builder(Component.literal(category.displayName()), unused -> {
                    this.controller.setCategory(category);
                    this.populateList(false);
                })
                .width(150)
                .build();
        button.active = !selected;
        return button;
    }

    private CycleButton<Boolean> moduleButton(final SettingsRowView row) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(row.displayOn())
                .withTooltip(value -> Tooltip.create(Component.literal(row.fullTooltip())))
                .create(
                        0,
                        0,
                        310,
                        20,
                        Component.literal(row.displayName()),
                        (cycle, value) -> {
                            if (!this.controller.setRequested(row.key(), value)) {
                                cycle.setValue(row.displayOn());
                                return;
                            }
                            this.controller.persist();
                            this.populateList(true);
                        });
        button.active = !row.locked();
        return button;
    }

    private CycleButton<FsrQualityPreset> fsrPresetButton(final FsrPresetRowView row) {
        CycleButton<FsrQualityPreset> button = CycleButton
                .builder((FsrQualityPreset preset) -> Component.literal(preset.displayName()))
                .withValues(FsrPresetRowView.values())
                .withInitialValue(row.preset())
                .withTooltip(value -> Tooltip.create(Component.literal(row.tooltip())))
                .create(
                        0,
                        0,
                        310,
                        20,
                        Component.translatableWithFallback("ultima.settings.fsr.preset", FsrPresetRowView.LABEL),
                        (cycle, value) -> {
                            if (!this.controller.setFsrPreset(value)) {
                                cycle.setValue(row.preset());
                                return;
                            }
                            this.controller.persist();
                            this.populateList(true);
                        });
        button.active = row.active();
        return button;
    }
}
