package dev.ultima.scenario;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

/**
 * One deterministic world scenario. It builds a section of the world, drives it for a fixed
 * number of ticks, and writes lines into the {@link Run}'s digest. The same scenario is run with
 * different Ultima module sets; the digests must be identical.
 */
interface Scenario {
    String name();

    /** Chunks that stay force-loaded while the scenario runs. */
    List<ChunkPos> chunks();

    /** Builds the scenario. Runs once, on the server thread, when every chunk is loaded. */
    void setup(Run run);

    /**
     * Advances the scenario by one server tick.
     *
     * @param tick ticks since {@link #setup}; 0 is the tick right after setup
     * @return true once all digest lines have been written
     */
    boolean tick(Run run, int tick);
}
