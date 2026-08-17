package dev.ultima.command;

import dev.ultima.Ultima;
import dev.ultima.config.UltimaConfig;
import dev.ultima.config.settings.UltimaCompatibilityReport;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Dedicated/integrated-server commands. The GUI is client-only; these dump the
 * same compatibility report the settings screen reads.
 */
public final class UltimaCommands {
    private UltimaCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("ultima")
                        .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                        .executes(context -> help(context.getSource()))
                        .then(Commands.literal("config").executes(context -> config(context.getSource())))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("compatibility")
                                        .executes(context -> compatibility(context.getSource()))))));
    }

    private static int help(final CommandSourceStack source) {
        source.sendSuccess(
                () -> Component.literal("Ultima commands: /ultima config, /ultima debug compatibility"),
                false);
        return 1;
    }

    private static int config(final CommandSourceStack source) {
        var path = UltimaConfig.resolveConfigPath();
        String location = path == null ? "(config directory unavailable)" : path.toAbsolutePath().toString();
        source.sendSuccess(
                () -> Component.literal(
                        "Ultima settings are stored in "
                                + location
                                + ". On the client, run /ultima config to open the in-game screen, or use Mod Menu."),
                false);
        return 1;
    }

    private static int compatibility(final CommandSourceStack source) {
        UltimaConfig config = UltimaConfig.get();
        Ultima.LOGGER.info("Ultima compatibility report:\n{}", UltimaCompatibilityReport.toJson(config));
        for (String line : UltimaCompatibilityReport.toChatLines(config).split("\n")) {
            if (!line.isBlank()) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        source.sendSuccess(() -> Component.literal("Full JSON written to the server log."), false);
        return 1;
    }
}
