package dev.ultima.scenario;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

/** A steady server load whose tick time is measured, as opposed to a {@link Scenario}, whose state is compared. */
interface BenchWorkload {
    String name();

    /** Chunks that stay force-loaded while the workload runs. */
    List<ChunkPos> chunks();

    /** Builds the load. Runs once, on the server thread, when every chunk is loaded. */
    void build(Run run);
}
