package dev.ultima.scenario;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the scenarios from the server's own tick loop: force-load the chunks, wait until they are
 * loaded, run every scenario side by side, write the digests, then stop the server.
 */
final class ScenarioRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-scenarios");
    private static final int LOAD_TIMEOUT_TICKS = 600;
    private static final int RUN_TIMEOUT_TICKS = 2400;
    private static final int SETTLE_TICKS = 20;

    private enum Phase { LOADING, RUNNING, DONE }

    private final String label;
    private final Path out;
    private final List<Scenario> scenarios;
    private final Set<String> finished = new HashSet<>();
    private final Digest digest = new Digest();

    private Phase phase = Phase.LOADING;
    private Run run;
    private int ticksInPhase;
    private String failure;

    ScenarioRunner(final String label, final Path out, final List<Scenario> scenarios) {
        this.label = label;
        this.out = out;
        this.scenarios = scenarios;
    }

    void onStarted(final MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (Scenario scenario : this.scenarios) {
            for (ChunkPos chunk : scenario.chunks()) {
                level.setChunkForced(chunk.x(), chunk.z(), true);
            }
        }
        this.run = new Run(server, level, this.digest);
        LOGGER.info("Scenario run '{}' started with {} scenarios", this.label, this.scenarios.size());
    }

    void onTick(final MinecraftServer server) {
        if (this.run == null || this.phase == Phase.DONE) {
            return;
        }
        this.ticksInPhase++;
        try {
            if (this.phase == Phase.LOADING) {
                this.tickLoading(server);
            } else {
                this.tickRunning(server);
            }
        } catch (RuntimeException | Error e) {
            this.failure = "exception: " + e;
            LOGGER.error("Scenario run '{}' failed", this.label, e);
            this.finish(server);
        }
    }

    private void tickLoading(final MinecraftServer server) {
        if (this.ticksInPhase > LOAD_TIMEOUT_TICKS) {
            this.failure = "chunks did not load within " + LOAD_TIMEOUT_TICKS + " ticks";
            this.finish(server);
            return;
        }
        if (this.ticksInPhase < SETTLE_TICKS || !this.allLoaded()) {
            return;
        }
        for (Scenario scenario : this.scenarios) {
            scenario.setup(this.run);
        }
        this.phase = Phase.RUNNING;
        this.ticksInPhase = 0;
    }

    private void tickRunning(final MinecraftServer server) {
        this.run.advanceTo(this.ticksInPhase);
        for (Scenario scenario : this.scenarios) {
            if (!this.finished.contains(scenario.name()) && scenario.tick(this.run, this.ticksInPhase - 1)) {
                this.finished.add(scenario.name());
            }
        }
        if (this.finished.size() == this.scenarios.size()) {
            this.finish(server);
        } else if (this.ticksInPhase > RUN_TIMEOUT_TICKS) {
            List<String> pending = new ArrayList<>();
            for (Scenario scenario : this.scenarios) {
                if (!this.finished.contains(scenario.name())) {
                    pending.add(scenario.name());
                }
            }
            this.failure = "scenarios did not finish within " + RUN_TIMEOUT_TICKS + " ticks: " + pending;
            this.finish(server);
        }
    }

    private boolean allLoaded() {
        for (Scenario scenario : this.scenarios) {
            for (ChunkPos chunk : scenario.chunks()) {
                if (!this.run.level().isLoaded(chunk.getWorldPosition())) {
                    return false;
                }
            }
        }
        return true;
    }

    private void finish(final MinecraftServer server) {
        this.phase = Phase.DONE;
        List<String> header = new ArrayList<>();
        header.add("label " + this.label);
        header.add("status " + (this.failure == null ? "ok" : "FAILED " + this.failure));
        UltimaConfig config = UltimaConfig.get();
        for (UltimaModules.Module module : UltimaModules.all()) {
            header.add("module " + module.key() + " enabled=" + config.isEnabled(module.key()));
        }
        for (String missing : Palette.missingNames()) {
            header.add("palette-missing " + missing);
        }
        try {
            Path summary = this.digest.write(this.out, this.label, header);
            LOGGER.info("Scenario run '{}' wrote {}", this.label, summary);
        } catch (IOException e) {
            LOGGER.error("Scenario run '{}' could not write its digest", this.label, e);
        }
        server.halt(false);
    }
}
