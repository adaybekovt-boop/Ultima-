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
    /** Floor block, then the block on top of it, one strip of 16 blocks per row of the arrangement. */
    private static final String[][] STRIPS = {
        {"minecraft:stone", null},
        {"minecraft:oak_planks", "minecraft:oak_fence"},
        {"minecraft:mossy_cobblestone", "minecraft:cobblestone_wall"},
        {"minecraft:sand", "minecraft:cactus"},
        {"minecraft:oak_log[axis=x]", null},
        {"minecraft:glass", null},
        {"minecraft:oak_leaves[persistent=true]", null},
        {"minecraft:oak_stairs[facing=east,half=bottom]", null},
        {"minecraft:oak_slab[type=top]", "minecraft:torch"},
        {"minecraft:glowstone", null},
        {"minecraft:packed_ice", null},
        {"minecraft:red_wool", "minecraft:white_carpet"}
    };
    /** The flat world has no terrain, so the test builds a small arrangement that crosses chunk and section borders. */
    private static final String[] CAMERA_POINTS = {
        "0 -50 -18 0 30",
        "22 -50 0 90 30",
        "-15 -46 15 225 30"
    };
    private static final int TELEPORT_SETTLE_TICKS = 240;
    private static final int FRAME_SETTLE_TICKS = 5;

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
            // A spectator neither falls nor takes damage, so every point is a still camera.
            server.runCommand("gamemode spectator");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            buildArrangement(server);
            int index = 0;
            for (String point : CAMERA_POINTS) {
                server.runCommand("tp @p " + point);
                context.waitTicks(TELEPORT_SETTLE_TICKS);
                world.getConnection().waitForChunksRender();
                context.waitTicks(FRAME_SETTLE_TICKS);
                shoot(context, shots, "03_world_point" + (++index));
            }
        }
    }

    private static void buildArrangement(final TestServerContext server) {
        int z = -6;
        for (String[] strip : STRIPS) {
            server.runCommand("fill -8 -60 " + z + " 7 -60 " + z + " " + strip[0]);
            if (strip[1] != null) {
                server.runCommand("fill -8 -59 " + z + " 7 -59 " + z + " " + strip[1]);
            }
            z++;
        }
        for (int x : new int[] {-8, 7}) {
            for (int pillarZ : new int[] {-6, 5}) {
                server.runCommand("fill " + x + " -60 " + pillarZ + " " + x + " -46 " + pillarZ + " minecraft:stone_bricks");
            }
        }
        server.runCommand("fill 9 -60 -7 15 -60 -1 minecraft:stone");
        server.runCommand("fill 10 -60 -6 14 -60 -2 minecraft:water");
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
