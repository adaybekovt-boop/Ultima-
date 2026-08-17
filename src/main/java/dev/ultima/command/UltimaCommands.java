package dev.ultima.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.ultima.Ultima;
import dev.ultima.config.UltimaConfig;
import dev.ultima.server.metrics.ServerMetrics;
import dev.ultima.server.metrics.ServerProfileJson;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Dedicated/integrated-server commands. {@code /ultima profile} is operator/console only.
 */
public final class UltimaCommands {
    public static final PermissionCheck PROFILE_PERMISSION = Commands.LEVEL_GAMEMASTERS;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private UltimaCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(root()));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("ultima")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("profile")
                        .requires(Commands.hasPermission(PROFILE_PERMISSION))
                        .executes(context -> startProfile(context.getSource(), ServerMetrics.DEFAULT_PROFILE_SECONDS))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, ServerMetrics.MAX_PROFILE_SECONDS))
                                .executes(context -> startProfile(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds"))))
                        .then(Commands.literal("stop")
                                .executes(context -> stopProfile(context.getSource()))));
    }

    public static boolean mayProfile(final PermissionSet permissions) {
        return permissions != null && PROFILE_PERMISSION.check(permissions);
    }

    public static boolean mayProfile(final CommandSourceStack source) {
        return source != null && mayProfile(source.permissions());
    }

    private static int help(final CommandSourceStack source) {
        source.sendSuccess(
                () -> Component.literal(
                        "Ultima commands: /ultima profile [seconds] (operators only, default "
                                + ServerMetrics.DEFAULT_PROFILE_SECONDS
                                + "s, max "
                                + ServerMetrics.MAX_PROFILE_SECONDS
                                + "s), /ultima profile stop"),
                false);
        return 1;
    }

    private static int startProfile(final CommandSourceStack source, final int seconds) {
        if (!UltimaConfig.get().isEnabled(ServerMetrics.MODULE_KEY) || !ServerMetrics.isEnabled()) {
            source.sendFailure(Component.literal(
                    "server_metrics is disabled. Set server_metrics=true in ultima.properties and restart."));
            return 0;
        }
        Consumer<String> feedback = line -> {
            source.sendSuccess(() -> Component.literal(line), true);
            Ultima.LOGGER.info("[ultima-profile] {}", line);
        };
        String message = ServerMetrics.startProfile(seconds, feedback, outputPath(source));
        if (message.startsWith("server_metrics is disabled")) {
            source.sendFailure(Component.literal(message));
            return 0;
        }
        return 1;
    }

    private static int stopProfile(final CommandSourceStack source) {
        String message = ServerMetrics.stopProfile();
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static Path outputPath(final CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server != null) {
            return server.getServerDirectory().resolve("ultima-server-profile-" + FILE_TIME.format(Instant.now()) + ".json");
        }
        return ServerProfileJson.defaultOutputPath();
    }
}
