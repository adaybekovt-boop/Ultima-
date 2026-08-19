package dev.ultima.client.command;

import dev.ultima.Ultima;
import dev.ultima.client.settings.UltimaConfigScreen;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.settings.UltimaCompatibilityReport;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client commands: {@code /ultima config} opens the settings screen.
 */
public final class UltimaClientCommands {
    private UltimaClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("ultima")
                        .executes(context -> help(context.getSource()))
                        .then(ClientCommands.literal("config").executes(context -> openConfig(context.getSource())))
                        .then(ClientCommands.literal("debug")
                                .then(ClientCommands.literal("compatibility")
                                        .executes(context -> compatibility(context.getSource()))))));
    }

    private static int help(final FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Ultima commands: /ultima config, /ultima debug compatibility"));
        return 1;
    }

    private static int openConfig(final FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        client.execute(() -> {
            Screen parent = client.gui.screen();
            client.gui.setScreen(new UltimaConfigScreen(parent));
        });
        source.sendFeedback(Component.literal("Opening Ultima settings."));
        return 1;
    }

    private static int compatibility(final FabricClientCommandSource source) {
        UltimaConfig config = UltimaConfig.get();
        Ultima.LOGGER.info("Ultima compatibility report:\n{}", UltimaCompatibilityReport.toJson(config));
        for (String line : UltimaCompatibilityReport.toChatLines(config).split("\n")) {
            if (!line.isBlank()) {
                source.sendFeedback(Component.literal(line));
            }
        }
        source.sendFeedback(Component.literal("Full JSON written to the log."));
        return 1;
    }
}
