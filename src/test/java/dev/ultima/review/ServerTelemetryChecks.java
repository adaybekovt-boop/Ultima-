package dev.ultima.review;

import dev.ultima.command.UltimaProfilePermissions;
import dev.ultima.config.UltimaModules;
import dev.ultima.server.metrics.MetricId;
import dev.ultima.server.metrics.SampleRing;
import dev.ultima.server.metrics.ServerMetrics;
import dev.ultima.server.metrics.ServerTelemetry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit coverage for server-side telemetry: counters, percentiles, profile timer, gating, and
 * operator-only {@code /ultima profile} permission.
 */
final class ServerTelemetryChecks {
    private ServerTelemetryChecks() {
    }

    static void run() {
        testSampleRingPercentiles();
        testCounterAccumulation();
        testDisabledHasNoClocksOrFiles();
        testDetailedModeTimerAndCap();
        testSemanticEventsOnlyWhenDetailed();
        testProfilePermission();
        testJsonExportShape();
        testFutureOptimizationHooks();
        testProductionHopperSleepTelemetry();
        testFragileInvokeRequireZero();
        System.out.println("Server telemetry checks passed.");
    }

    private static void testSampleRingPercentiles() {
        SampleRing ring = new SampleRing(128);
        for (int value = 1; value <= 100; value++) {
            ring.add(value);
        }
        assertEquals(100, ring.size(), "ring holds 100 samples");
        assertEquals(5050, ring.sum(), "1..100 sum");
        assertEquals(50, SampleRing.percentile(sorted(ring), 0.50), "p50 of 1..100");
        assertEquals(95, SampleRing.percentile(sorted(ring), 0.95), "p95 of 1..100");
        assertEquals(99, SampleRing.percentile(sorted(ring), 0.99), "p99 of 1..100");
        assertEquals(100, ring.max(), "max of 1..100");
        assertEquals(50.5, ring.average(), 1e-9, "average of 1..100");

        SampleRing wrap = new SampleRing(4);
        wrap.add(1);
        wrap.add(2);
        wrap.add(3);
        wrap.add(4);
        wrap.add(10);
        assertEquals(4, wrap.size(), "capacity is fixed");
        assertEquals(19, wrap.sum(), "evicted 1, remaining 2+3+4+10");
        assertEquals(10, wrap.last(), "last written value");
    }

    private static void testCounterAccumulation() {
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(true, clock::get, 32);
        long entitySum = 0L;
        long totalSum = 0L;
        for (int tick = 1; tick <= 10; tick++) {
            long entityNs = tick * 1_000L;
            long otherNs = 250L;
            ServerMetrics.beginTick(tick, 2);
            ServerMetrics.begin(MetricId.TICK_TOTAL);
            ServerMetrics.begin(MetricId.TICK_ENTITIES);
            clock.addAndGet(entityNs);
            ServerMetrics.end(MetricId.TICK_ENTITIES);
            clock.addAndGet(otherNs);
            ServerMetrics.end(MetricId.TICK_TOTAL);
            ServerMetrics.addCount(MetricId.TRACKING_ADDED, tick);
            ServerMetrics.endTick();
            entitySum += entityNs;
            totalSum += entityNs + otherNs;
            assertEquals(entityNs, ServerMetrics.last(MetricId.TICK_ENTITIES), "last entity ns tick " + tick);
            assertEquals(entityNs + otherNs, ServerMetrics.last(MetricId.TICK_TOTAL), "last total ns tick " + tick);
            assertEquals(tick, ServerMetrics.last(MetricId.TRACKING_ADDED), "last added count tick " + tick);
        }
        assertEquals(entitySum, ServerMetrics.ring(MetricId.TICK_ENTITIES).sum(), "entity window sum");
        assertEquals(totalSum, ServerMetrics.ring(MetricId.TICK_TOTAL).sum(), "total window sum");
        assertEquals(10, ServerMetrics.ring(MetricId.TICK_TOTAL).size(), "ten total samples");
        ServerMetrics.MetricSnapshot total = ServerMetrics.snapshotOf(MetricId.TICK_TOTAL);
        assertTrue(total.histogram(), "tick.total stores a histogram");
        assertTrue(
                total.p50() != null && total.p95() != null && total.p99() != null && total.max() != null,
                "percentiles are present for histogram metrics");
        assertEquals(totalSum / 10.0, total.windowAvg(), 1e-9, "average total ns");
        assertEquals(10 * 11 / 2, ServerMetrics.ring(MetricId.TRACKING_ADDED).sum(), "1..10 added sum");
    }

    private static void testDisabledHasNoClocksOrFiles() {
        Path output = tempFile("ultima-server-metrics-disabled", "should-not-exist.json");
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(false, clock::get, 8);
        int clocks = ServerMetrics.phaseClocksCreated();
        ServerMetrics.beginTick(1, 0);
        ServerMetrics.begin(MetricId.TICK_TOTAL);
        clock.addAndGet(1_000_000L);
        ServerMetrics.end(MetricId.TICK_TOTAL);
        ServerMetrics.addCount(MetricId.TRACKING_ADDED, 99);
        ServerTelemetry.recordHopperWakeAdjacentInventory();
        ServerMetrics.endTick();
        String started = ServerMetrics.startProfile(30, line -> {}, output);
        assertTrue(started.contains("disabled"), "profile start is refused when the module is off");
        assertEquals(clocks, ServerMetrics.phaseClocksCreated(), "disabled path must not create phase clocks");
        assertEquals(0, ServerMetrics.last(MetricId.TICK_TOTAL), "disabled counters stay zero");
        assertEquals(0, ServerMetrics.ring(MetricId.TICK_TOTAL).size(), "disabled path does not write the ring");
        assertEquals(0, ServerMetrics.jsonFilesWritten(), "disabled path does not write JSON");
        assertTrue(!Files.exists(output), "disabled path does not create an export file");
        assertTrue(!ServerMetrics.isProfiling(), "profile session must not stick when disabled");
    }

    private static void testDetailedModeTimerAndCap() {
        assertEquals(60, ServerMetrics.clampProfileSeconds(0), "omitted/zero seconds use the default");
        assertEquals(60, ServerMetrics.clampProfileSeconds(-5), "negative seconds use the default");
        assertEquals(300, ServerMetrics.clampProfileSeconds(10_000), "seconds are capped at the maximum");
        assertEquals(1, ServerMetrics.clampProfileSeconds(1), "one second is allowed");

        Path output = tempFile("ultima-server-metrics-profile", "profile.json");
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(true, clock::get, 64);
        ServerMetrics.setLagTickThresholdNs(0L);
        List<String> lines = new ArrayList<>();
        ServerMetrics.startProfile(2, lines::add, output);
        assertTrue(ServerMetrics.isProfiling(), "profile is active after start");
        assertTrue(ServerMetrics.isDetailed(), "detailed tracing is on during a profile");

        for (int tick = 1; tick <= 8; tick++) {
            ServerMetrics.beginTick(tick, 1);
            ServerMetrics.begin(MetricId.TICK_TOTAL);
            ServerMetrics.begin(MetricId.CHUNK_GENERATE);
            clock.addAndGet(400_000_000L);
            ServerMetrics.end(MetricId.CHUNK_GENERATE);
            clock.addAndGet(100_000_000L);
            ServerMetrics.end(MetricId.TICK_TOTAL);
            ServerMetrics.endTick();
        }

        assertTrue(!ServerMetrics.isProfiling(), "profile auto-stops when the wall-clock budget elapses");
        assertTrue(!ServerMetrics.isDetailed(), "detailed tracing does not remain enabled after the timer");
        assertTrue(Files.exists(output), "JSON is written when the profile ends");
        String json = read(output);
        assertTrue(json.contains("\"requestedSeconds\": 2"), "JSON records the clamped requested duration");
        assertTrue(json.contains("\"kind\": \"ultima_server_profile\""), "JSON kind is stable for A/B");
        assertEquals(1, ServerMetrics.jsonFilesWritten(), "exactly one JSON file is written");

        ServerMetrics.resetForTest(true, clock::get, 8);
        Path capped = output.resolveSibling("capped.json");
        ServerMetrics.startProfile(50_000, lines::add, capped);
        ServerMetrics.stopProfile();
        String cappedJson = read(capped);
        assertTrue(cappedJson.contains("\"requestedSeconds\": 300"), "startProfile clamps to the maximum");
        assertTrue(!ServerMetrics.isDetailed(), "stop clears detailed mode");
    }

    private static void testSemanticEventsOnlyWhenDetailed() {
        AtomicLong clock = new AtomicLong();
        Path output = tempFile("ultima-server-metrics-events", "events.json");
        ServerMetrics.resetForTest(true, clock::get, 8);
        ServerTelemetry.recordHopperWakeAdjacentInventory();
        ServerMetrics.beginTick(1, 0);
        ServerMetrics.endTick();
        assertEquals(1, ServerMetrics.last(MetricId.BE_WAKEUPS), "always-on wakeup counter still increments");

        ServerMetrics.startProfile(60, line -> {}, output);
        ServerTelemetry.recordHopperWakeAdjacentInventory();
        ServerTelemetry.recordTrackerRecalcSectionBoundary();
        ServerTelemetry.recordChunkSerializeCacheMiss();
        ServerMetrics.beginTick(2, 1);
        ServerMetrics.begin(MetricId.TICK_TOTAL);
        clock.addAndGet(1_000L);
        ServerMetrics.end(MetricId.TICK_TOTAL);
        ServerMetrics.addCount(MetricId.CHUNKS_GENERATED, 12);
        ServerMetrics.addCount(MetricId.PACKETS_PREPARED_BYTES, 3_000_000L);
        ServerMetrics.recordPlayerEnteredChunk("Alice");
        ServerMetrics.recordPlayerEnteredChunk("Alice");
        ServerMetrics.recordPlayerEnteredChunk("Bob");
        ServerMetrics.endTick();
        ServerMetrics.stopProfile();
        String json = read(output);
        assertTrue(json.contains("hopper woke because adjacent inventory changed"), "hopper semantic event");
        assertTrue(
                json.contains("entity tracker recalculated visibility because player crossed section boundary"),
                "tracker semantic event");
        assertTrue(
                json.contains("chunk serialization cache missed because block state version changed"),
                "serialize-cache semantic event");
        assertTrue(json.contains("player Alice moved into 2 new chunks"), "per-player chunk-enter event");
        assertTrue(json.contains("player Bob moved into 1 new chunks"), "second player chunk-enter event");
        assertTrue(json.contains("12 chunks generated"), "generated-count event");
        assertTrue(json.contains("packets prepared"), "packet-prepare event");
    }

    private static void testProfilePermission() {
        assertTrue(!UltimaProfilePermissions.mayProfile(0), "permission level ALL cannot profile");
        assertTrue(!UltimaProfilePermissions.mayProfile(1), "moderators cannot profile");
        assertTrue(UltimaProfilePermissions.mayProfile(2), "gamemaster/operator can profile");
        assertTrue(UltimaProfilePermissions.mayProfile(4), "owner/console can profile");
        assertEquals(2, UltimaProfilePermissions.OPERATOR_LEVEL_ID, "operator level is GAMEMASTERS (2)");
        String commandSource = read(Path.of("src/main/java/dev/ultima/command/UltimaCommands.java"));
        assertTrue(
                commandSource.contains("Commands.LEVEL_GAMEMASTERS"),
                "/ultima profile must require Commands.LEVEL_GAMEMASTERS");
        assertTrue(
                commandSource.contains("requires(Commands.hasPermission(PROFILE_PERMISSION))"),
                "the profile literal must be permission-gated, not the whole /ultima tree");
        assertTrue(
                commandSource.contains("Commands.LEVEL_ALL"),
                "/ultima config and debug compatibility stay LEVEL_ALL; profile stays GAMEMASTERS");
        assertTrue(
                !UltimaModules.byKey("server_metrics").incompatibleMods().contains("lithium"),
                "server_metrics stays enabled with Lithium; fragile INVOKE injects use require=0");
        assertTrue(
                UltimaModules.isInstrumentation("server_metrics"),
                "server_metrics is classified as instrumentation like terrain_metrics");
        assertTrue(
                UltimaModules.kind(UltimaModules.byKey("server_metrics")) == UltimaModules.Kind.INSTRUMENTATION,
                "server_metrics kind is instrumentation");
        assertTrue(UltimaModules.byKey("server_metrics").enabledByDefault(), "server_metrics is default-on");
        assertTrue(!UltimaModules.byKey("server_metrics").clientOnly(), "server_metrics runs on dedicated servers");
    }

    private static void testJsonExportShape() {
        Path output = tempFile("ultima-server-metrics-json", "session.json");
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(true, clock::get, 16);
        ServerMetrics.setLagTickThresholdNs(1_000L);
        ServerMetrics.startProfile(60, line -> {}, output);
        ServerMetrics.beginTick(42, 3);
        ServerMetrics.begin(MetricId.TICK_TOTAL);
        clock.addAndGet(5_000L);
        ServerMetrics.end(MetricId.TICK_TOTAL);
        ServerMetrics.endTick();
        ServerMetrics.stopProfile();
        String json = read(output);
        assertTrue(json.contains("\"schemaVersion\": 1"), "schemaVersion");
        assertTrue(json.contains("\"purpose\": \"diagnostic_not_optimization\""), "purpose field");
        assertTrue(json.contains("\"tick.total\""), "tick.total counter");
        assertTrue(json.contains("\"p95\""), "histogram percentiles");
        assertTrue(json.contains("\"ticks\""), "per-tick detailed array");
        assertTrue(json.contains("\"otherNs\""), "other remainder");
        assertTrue(json.contains("\"lagTicksOverThreshold\""), "lag-tick count");
        assertTrue(json.contains("\"topSubsystems\""), "top subsystem list");
        assertTrue(json.contains("\"environment\""), "environment block");
        assertTrue(json.contains("\"lagTicksOverThreshold\": 1"), "the 5us tick is over a 1us threshold");
        ServerMetrics.setLagTickThresholdNs(ServerMetrics.DEFAULT_LAG_TICK_NS);
    }

    private static void testFutureOptimizationHooks() {
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(true, clock::get, 4);
        ServerTelemetry.recordBeSleepingCount(17);
        ServerTelemetry.recordBeWakeup();
        ServerTelemetry.recordAiCleanSkip();
        ServerTelemetry.recordAiCleanSkip();
        ServerTelemetry.recordAiInvalidation();
        ServerMetrics.beginTick(1, 0);
        ServerMetrics.endTick();
        assertEquals(17, ServerMetrics.last(MetricId.BE_SLEEPING), "sleeping BE gauge");
        assertEquals(1, ServerMetrics.last(MetricId.BE_WAKEUPS), "wakeup count");
        assertEquals(2, ServerMetrics.last(MetricId.AI_CLEAN_SKIPS), "AI skip counter");
        assertEquals(1, ServerMetrics.last(MetricId.AI_INVALIDATIONS), "AI invalidation counter");
    }

    private static void testProductionHopperSleepTelemetry() {
        AtomicLong clock = new AtomicLong();
        ServerMetrics.resetForTest(true, clock::get, 4);
        dev.ultima.sleeping.BlockEntitySleepMetrics.resetForTests();
        dev.ultima.sleeping.BlockEntitySleepMetrics.onSleepEnter();
        dev.ultima.sleeping.BlockEntitySleepMetrics.onWake(dev.ultima.sleeping.WakeChannel.SELF_INVENTORY);
        dev.ultima.sleeping.BlockEntitySleepMetrics.onWake(dev.ultima.sleeping.WakeChannel.NEIGHBOR_INVENTORY);
        dev.ultima.sleeping.BlockEntitySleepMetrics.onThrashTrip();
        ServerMetrics.beginTick(1, 0);
        ServerMetrics.endTick();
        assertEquals(0, ServerMetrics.last(MetricId.BE_SLEEPING), "enter then two wakes leave the sleeping gauge at 0");
        assertEquals(2, ServerMetrics.last(MetricId.BE_WAKEUPS), "neighbor inventory does not double-count wakeups");
        assertEquals(1, ServerMetrics.last(MetricId.BE_THRASHES), "thrash pin exports be.thrashes");
    }

    /**
     * Mixin-wiring: Lithium-fragile INVOKE targets use {@code require = 0} so apply does not fail.
     * This does not prove the inject still matches with Lithium loaded.
     */
    private static void testFragileInvokeRequireZero() {
        String serverLevel = read(Path.of("src/main/java/dev/ultima/mixin/server_metrics/ServerLevelMixin.java"));
        assertTrue(
                serverLevel.contains("EntityTickList;forEach") && serverLevel.contains("require = 0"),
                "EntityTickList.forEach injects must not fail Mixin apply when Lithium rewrites the call");
        String tracked = read(Path.of("src/main/java/dev/ultima/mixin/server_metrics/ChunkMapTrackedEntityMixin.java"));
        assertTrue(
                tracked.contains("ServerEntity;addPairing") && tracked.contains("require = 0"),
                "ServerEntity.addPairing inject must not fail Mixin apply when Lithium rewrites pairing");
        assertTrue(
                tracked.contains("ServerEntity;removePairing") && tracked.contains("require = 0"),
                "ServerEntity.removePairing inject uses the same require=0 fail-soft");
    }

    private static Path tempFile(final String dirPrefix, final String fileName) {
        try {
            return Files.createTempDirectory(dirPrefix).resolve(fileName);
        } catch (IOException e) {
            throw new AssertionError("could not create temp dir " + dirPrefix, e);
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static long[] sorted(final SampleRing ring) {
        long[] copy = ring.copy();
        java.util.Arrays.sort(copy);
        return copy;
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(final long expected, final long actual, final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(final String expected, final String actual, final String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(
            final double expected, final double actual, final double epsilon, final String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
