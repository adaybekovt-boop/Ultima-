package dev.ultima.client.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;

/**
 * {@link OptionsList} with a public reset so category/toggle refreshes do not rebuild the screen.
 */
public final class UltimaSettingsList extends OptionsList {
    public UltimaSettingsList(final Minecraft minecraft, final int width, final OptionsSubScreen screen) {
        super(minecraft, width, screen);
    }

    public void reset() {
        this.clearEntries();
        this.setScrollAmount(0.0);
    }

    public void resetPreservingScroll() {
        this.clearEntries();
        this.setScrollAmount(0.0);
    }
}
