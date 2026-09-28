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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

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
        {"minecraft:oak_slab[type=top]", null},
        {"minecraft:glowstone", null},
        {"minecraft:packed_ice", null},
        {"minecraft:red_wool", "minecraft:white_carpet"}
    };
    /**
     * The flat world has no terrain, so the test builds a small arrangement that crosses chunk and section borders.
     * It has no water, fire or torches: their animations and particles are random or depend on the tick count, and
     * would make two identical runs differ.
     */
    private static final String[] CAMERA_POINTS = {
        "0 -50 -18 0 30",
        "22 -50 0 90 30",
        "-15 -46 15 225 30"
    };
    private static final int TELEPORT_SETTLE_TICKS = 240;
    private static final int FRAME_SETTLE_TICKS = 5;
    private static final int LEFT_MOUSE_BUTTON = 0;

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
            openCategory(context, category);
            shoot(context, shots, "02_settings_" + category);
        }
        context.setScreen(TitleScreen::new);
        context.waitForScreen(TitleScreen.class);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            // A spectator neither falls nor takes damage, so every point is a still camera.
            server.runCommand("gamemode spectator @p");
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

    /**
     * Clicks the category button with the mouse. Fabric's own button lookup does not see the buttons of a scrolling
     * list, so this walks the widget tree, and it fails when the button is missing or does not become selected.
     */
    private static void openCategory(final ClientGameTestContext context, final String category) {
        String text = Component.translatable("ultima.category." + category).getString();
        double[] center = context.computeOnClient(client -> {
            AbstractWidget button = findButton(client.gui.screen(), text);
            if (button == null) {
                throw new AssertionError("the settings screen has no button '" + text + "'");
            }
            double scale = (double) client.getWindow().getWidth() / client.getWindow().getGuiScaledWidth();
            return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(center[0], center[1]);
        context.getInput().pressMouse(LEFT_MOUSE_BUTTON);
        context.waitTicks(3);
        boolean selected = context.computeOnClient(client -> {
            AbstractWidget button = findButton(client.gui.screen(), text);
            return button != null && !button.active;
        });
        if (!selected) {
            throw new AssertionError("clicking '" + text + "' did not select the category");
        }
    }

    private static AbstractWidget findButton(final ContainerEventHandler parent, final String text) {
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget && text.equals(widget.getMessage().getString())) {
                return widget;
            }
            if (child instanceof ContainerEventHandler nested) {
                AbstractWidget found = findButton(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
