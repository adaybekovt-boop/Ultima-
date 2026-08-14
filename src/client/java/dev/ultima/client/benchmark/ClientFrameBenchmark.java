package dev.ultima.client.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in client frame recorder. Both sides of an A/B run keep this module enabled so recorder
 * overhead is identical; optimization modules are the only changed variables.
 */
public final class ClientFrameBenchmark {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-client-benchmark");
    private static final boolean ENABLED = Boolean.getBoolean("ultima.clientBenchmark");
    private static final int WARMUP_FRAMES = positiveIntegerProperty("ultima.clientBenchmark.warmupFrames", 1200);
    private static final int SAMPLE_FRAMES = positiveIntegerProperty("ultima.clientBenchmark.sampleFrames", 12000);
    private static final Path OUTPUT = Path.of(
            System.getProperty("ultima.clientBenchmark.output", "run/ultima-client-benchmark.json"));
    private static final long[] FRAME_TIMES = new long[SAMPLE_FRAMES];

    private static int readyFrames;
    private static int samples;
    private static long frameStart;
    private static boolean complete;

    private ClientFrameBenchmark() {
    }

    public static void beginFrame(final boolean worldReady) {
        if (!ENABLED || complete || !worldReady) {
            frameStart = 0L;
            return;
        }
        frameStart = System.nanoTime();
    }

    public static void endFrame() {
        long start = frameStart;
        frameStart = 0L;
        if (start == 0L || complete) {
            return;
        }

        long elapsed = System.nanoTime() - start;
        if (readyFrames++ < WARMUP_FRAMES) {
            if (readyFrames == WARMUP_FRAMES) {
                ClientOptimizationCounters.reset();
            }
            return;
        }

        FRAME_TIMES[samples++] = elapsed;
        if (samples == FRAME_TIMES.length) {
            complete = true;
            writeResults();
        }
    }

    private static void writeResults() {
        long[] sorted = FRAME_TIMES.clone();
        Arrays.sort(sorted);
        long total = 0L;
        for (long frameTime : FRAME_TIMES) {
            total += frameTime;
        }

        double averageNs = (double)total / SAMPLE_FRAMES;
        double averageFps = 1_000_000_000.0 / averageNs;
        double medianNs = percentile(sorted, 0.5);
        double p95Ns = percentile(sorted, 0.95);
        double p99Ns = percentile(sorted, 0.99);
        double onePercentLowFps = 1_000_000_000.0 / slowestAverage(sorted, 0.01);
        double pointOnePercentLowFps = 1_000_000_000.0 / slowestAverage(sorted, 0.001);

        StringBuilder json = new StringBuilder(32_768);
        json.append("{\n")
                .append("  \"warmupFrames\": ").append(WARMUP_FRAMES).append(",\n")
                .append("  \"sampleFrames\": ").append(SAMPLE_FRAMES).append(",\n")
                .append("  \"averageFps\": ").append(averageFps).append(",\n")
                .append("  \"medianFps\": ").append(1_000_000_000.0 / medianNs).append(",\n")
                .append("  \"onePercentLowFps\": ").append(onePercentLowFps).append(",\n")
                .append("  \"pointOnePercentLowFps\": ").append(pointOnePercentLowFps).append(",\n")
                .append("  \"averageFrameTimeMs\": ").append(averageNs / 1_000_000.0).append(",\n")
                .append("  \"p95FrameTimeMs\": ").append(p95Ns / 1_000_000.0).append(",\n")
                .append("  \"p99FrameTimeMs\": ").append(p99Ns / 1_000_000.0).append(",\n")
                .append("  \"cpuFrameTimeAvailable\": false,\n")
                .append("  \"chunkMatrixCopiesAvoided\": ").append(ClientOptimizationCounters.chunkMatrixCopiesAvoided()).append(",\n")
                .append("  \"chunkLayerArraysAvoided\": ").append(ClientOptimizationCounters.chunkLayerArraysAvoided()).append(",\n")
                .append("  \"averageChunkMatrixCopiesAvoidedPerFrame\": ")
                .append((double)ClientOptimizationCounters.chunkMatrixCopiesAvoided() / SAMPLE_FRAMES)
                .append(",\n")
                .append("  \"averageChunkLayerArraysAvoidedPerFrame\": ")
                .append((double)ClientOptimizationCounters.chunkLayerArraysAvoided() / SAMPLE_FRAMES)
                .append(",\n")
                .append("  \"frameTimesNs\": [");
        for (int i = 0; i < FRAME_TIMES.length; i++) {
            if (i != 0) {
                json.append(',');
            }
            json.append(FRAME_TIMES[i]);
        }
        json.append("]\n}\n");

        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);
            LOGGER.info(
                    "Client benchmark complete: {} frames, average {} FPS, 1% low {} FPS, output {}",
                    SAMPLE_FRAMES,
                    averageFps,
                    onePercentLowFps,
                    OUTPUT.toAbsolutePath());
        } catch (IOException | SecurityException e) {
            LOGGER.error("Could not write client benchmark output {}", OUTPUT.toAbsolutePath(), e);
        }
    }

    private static double percentile(final long[] sorted, final double percentile) {
        int index = Math.max(0, (int)Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static double slowestAverage(final long[] sorted, final double fraction) {
        int count = Math.max(1, (int)Math.ceil(sorted.length * fraction));
        long total = 0L;
        for (int i = sorted.length - count; i < sorted.length; i++) {
            total += sorted[i];
        }
        return (double)total / count;
    }

    private static int positiveIntegerProperty(final String key, final int defaultValue) {
        int value = Integer.getInteger(key, defaultValue);
        return value > 0 ? value : defaultValue;
    }
}
