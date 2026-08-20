package dev.ultima.mixin.settings_ui;

import dev.ultima.client.settings.UltimaConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback title-screen entry when Mod Menu is not installed. Lives in the
 * {@code settings_ui} module package so {@link dev.ultima.config.UltimaMixinPlugin}
 * can skip it when that module is off. Client UI only; no packets or registries.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(final Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void ultimaAddSettingsButton(final CallbackInfo ci) {
        if (dev.ultima.config.LoadedModCache.isLoaded("modmenu")) {
            return;
        }
        this.addRenderableWidget(Button.builder(
                        Component.translatableWithFallback("ultima.settings.title_button", "Ultima..."),
                        button -> this.minecraft.gui.setScreen(new UltimaConfigScreen(this)))
                .bounds(4, this.height - 24, 80, 20)
                .tooltip(Tooltip.create(Component.translatableWithFallback(
                        "ultima.settings.title_button.tooltip",
                        "Ultima optimization modules")))
                .build());
    }
}
