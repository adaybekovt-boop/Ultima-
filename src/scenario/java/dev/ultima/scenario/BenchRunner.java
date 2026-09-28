package dev.ultima.scenario;

import dev.ultima.config.UltimaConfig;
import dev.ultima.config.UltimaModules;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures the server's own tick time under a {@link BenchWorkload}: build the load, warm up, then
 * record the wall time and the allocated bytes of every tick, write them as CSV with a summary,
 * and stop the server. Timing brackets the whole tick, so it includes everything a module can
 * speed up or slow down, and none of the sleeping between ticks.
 */
final class BenchRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-bench");
    private static final int LOAD_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 40;

    private enum Phase { LOADING, WARMUP, MEASURING, DONE }

    private final String label;
    private final Path out;
    private final BenchWorkload workload;
    private final int warmupTicks;
    private final int measuredTicks;
    private final long[] tickNanos;
    private final long[] tickAllocated;
    private final com.sun.management.ThreadMXBean threads;

    private Phase phase = Phase.LOADING;
    private Run run;
    private int ticksInPhase;
    private int measured;
    private long startNanos;
    private long startAllocated;
    private String failure;

    BenchRunner(final String label, final Path out, final BenchWorkload workload, final int warmupTicks,
            final int measuredTicks) {
        this.label = label;
        this.out = out;
        this.workload = workload;
        this.warmupTicks = warmupTicks;
        this.measuredTicks = measuredTicks;
        this.tickNanos = new long[measuredTicks];
        this.tickAllocated = new long[measuredTicks];
        this.threads = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun ? sun : null;
    }

    void onStarted(final MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (ChunkPos chunk : this.workload.chunks()) {
            level.setChunkForced(chunk.x(), chunk.z(), true);
        }
        this.run = new Run(server, level, new Digest());
        LOGGER.info("Bench '{}' workload={} warmup={} measured={}", this.label, this.workload.name(),
                this.warmupTicks, this.measuredTicks);
    }

    void onStartTick(final MinecraftServer server) {
        if (this.phase == Phase.MEASURING) {
            this.startAllocated = this.allocated();
            this.startNanos = System.nanoTime();
        }
    }

    void onEndTick(final MinecraftServer server) {
        if (this.run == null || this.phase == Phase.DONE) {
            return;
        }
        long elapsed = System.nanoTime() - this.startNanos;
        long allocated = this.allocated() - this.startAllocated;
        this.ticksInPhase++;
        try {
            switch (this.phase) {
                case LOADING -> this.tickLoading(server);
                case WARMUP -> {
                    if (this.ticksInPhase >= this.warmupTicks) {
                        this.phase = Phase.MEASURING;
                        this.ticksInPhase = 0;
                    }
                }
                case MEASURING -> {
                    this.tickNanos[this.measured] = elapsed;
                    this.tickAllocated[this.measured] = allocated;
                    this.measured++;
                    if (this.measured >= this.measuredTicks) {
                        this.finish(server);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException | Error e) {
            this.failure = "exception: " + e;
            LOGGER.error("Bench '{}' failed", this.label, e);
            this.finish(server);
        }
    }

    private void tickLoading(final MinecraftServer server) {
        if (this.ticksInPhase > LOAD_TIMEOUT_TICKS) {
            this.failure = "chunks did not load within " + LOAD_TIMEOUT_TICKS + " ticks";
            this.finish(server);
            return;
        }
        if (this.ticksInPhase < SETTLE_TICKS) {
            return;
        }
        for (ChunkPos chunk : this.workload.chunks()) {
            if (!this.run.level().isLoaded(chunk.getWorldPosition())) {
                return;
            }
        }
        this.workload.build(this.run);
        this.phase = Phase.WARMUP;
        this.ticksInPhase = 0;
    }

    private long allocated() {
        if (this.threads == null) {
            return 0L;
        }
        try {
            return this.threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (UnsupportedOperationException e) {
            return 0L;
        }
    }

    private void finish(final MinecraftServer server) {
        this.phase = Phase.DONE;
        int count = this.failure == null ? this.measured : 0;
        try {
            Files.createDirectories(this.out);
            List<String> csv = new ArrayList<>(count + 1);
            csv.add("tick_ns,alloc_bytes");
            for (int i = 0; i < count; i++) {
                csv.add(this.tickNanos[i] + "," + this.tickAllocated[i]);
            }
            Files.write(this.out.resolve(this.label + ".bench.csv"), csv, StandardCharsets.UTF_8);
            Files.write(this.out.resolve(this.label + ".bench.summary.txt"), this.summary(count), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Bench '{}' could not write its results", this.label, e);
        }
        server.halt(false);
    }

    private List<String> summary(final int count) {
        List<String> lines = new ArrayList<>();
        lines.add("label " + this.label);
        lines.add("workload " + this.workload.name());
        lines.add("status " + (this.failure == null ? "ok" : "FAILED " + this.failure));
        lines.add("ticks " + count);
        if (count > 0) {
            long[] sorted = Arrays.copyOf(this.tickNanos, count);
            Arrays.sort(sorted);
            long total = 0;
            long allocated = 0;
            for (int i = 0; i < count; i++) {
                total += this.tickNanos[i];
                allocated += this.tickAllocated[i];
            }
            lines.add("mspt_mean " + total / (double) count / 1.0e6);
            lines.add("mspt_median " + percentile(sorted, 0.50) / 1.0e6);
            lines.add("mspt_p95 " + percentile(sorted, 0.95) / 1.0e6);
            lines.add("mspt_p99 " + percentile(sorted, 0.99) / 1.0e6);
            lines.add("alloc_mean_bytes " + allocated / (double) count);
        }
        UltimaConfig config = UltimaConfig.get();
        for (UltimaModules.Module module : UltimaModules.all()) {
            lines.add("module " + module.key() + " enabled=" + config.isEnabled(module.key()));
        }
        return lines;
    }

    private static double percentile(final long[] sorted, final double fraction) {
        double index = (sorted.length - 1) * fraction;
        int low = (int) Math.floor(index);
        int high = Math.min(low + 1, sorted.length - 1);
        double weight = index - low;
        return sorted[low] * (1.0 - weight) + sorted[high] * weight;
    }
}
