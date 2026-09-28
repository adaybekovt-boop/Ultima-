package dev.ultima.clienttest;

import dev.ultima.client.settings.UltimaConfigScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Starts the real client with Ultima loaded, opens Ultima's settings screen and clicks through its
 * categories (which exercises every translation key the screen uses), creates a world, visits three
 * camera points and saves a screenshot at each step. The screenshots of two runs with different
 * module sets are compared by scripts/compare-screenshots.py.
 */
public final class UltimaClientGameTest implements FabricClientGameTest {
    private static final String[] CAMERA_POINTS = {
        "0 90 0 0 20",
        "192 110 -160 135 25",
        "-240 100 288 270 15"
    };
    private static final int SETTLE_TICKS = 240;

    @Override
    public void runTest(final ClientGameTestContext context) {
        Path shots = Path.of(System.getProperty("ultima.clienttest.out", "build/clienttest"))
                .resolve(System.getProperty("ultima.clienttest.label", "run"));

        context.waitForScreen(TitleScreen.class);
        shoot(context, shots, "01_title");

        context.setScreen(() -> new UltimaConfigScreen(null));
        context.waitForScreen(UltimaConfigScreen.class);
        context.waitTicks(5);
        shoot(context, shots, "02_settings_rendering");
        for (String category : new String[] {"simulation", "killer_modules", "advanced"}) {
            // Category buttons are found by translation key; a button that is not translatable is skipped.
            context.tryClickScreenButton("ultima.category." + category);
            context.waitTicks(3);
            shoot(context, shots, "02_settings_" + category);
        }
        context.setScreen(TitleScreen::new);
        context.waitForScreen(TitleScreen.class);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            int index = 0;
            for (String point : CAMERA_POINTS) {
                server.runCommand("tp @p " + point);
                context.waitTicks(SETTLE_TICKS);
                shoot(context, shots, "03_world_point" + (++index));
            }
        }
    }

    private static void shoot(final ClientGameTestContext context, final Path directory, final String name) {
        Path taken = context.takeScreenshot(name);
        try {
            Files.createDirectories(directory);
            Files.copy(taken, directory.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("could not save screenshot " + name, e);
        }
    }
}
