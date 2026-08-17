package dev.ultima.client.settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Soft-dependency entrypoint. Fabric only loads this when Mod Menu is present.
 */
public final class UltimaModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return UltimaConfigScreen::new;
    }
}
