package dev.ultima.server.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Cheap always-on server subsystem counters plus an opt-in detailed trace.
 *
 * <p>Always-on hot path: one volatile boolean, {@code nanoTime} begin/end, and an
 * {@code AtomicLong.addAndGet} per instrumented interval. No allocations. Mixins of a disabled
 * {@code server_metrics} module are skipped entirely by {@code UltimaMixinPlugin}.
 *
 * <p>Expected cost when enabled: on the order of two {@code nanoTime} calls plus one atomic add per
 * instrumented phase (roughly a dozen server-thread phases per tick, plus worker/Netty intervals
 * when those run). That is tens of microseconds per tick, not a performance claim.
 */
public final class ServerMetrics {
    public static final String MODULE_KEY = "server_metrics";
    public static final int DEFAULT_RING_SIZE = 1200;
    public static final int DEFAULT_PROFILE_SECONDS = 60;
    public static final int MAX_PROFILE_SECONDS = 300;
    public static final long DEFAULT_LAG_TICK_NS = 50_000_000L;
    public static final String LAG_TICK_PROPERTY = "ultima.serverMetrics.lagTickMs";

    private static final int METRIC_COUNT = MetricId.all().length;
    private static final int MAX_EVENTS_PER_TICK = 64;
    private static final int MAX_PLAYERS_TRACKED = 16;
    private static final int MAX_DETAILED_TICKS = MAX_PROFILE_SECONDS * 20;
    private static final java.util.concurrent.atomic.AtomicInteger PHASE_CLOCKS_CREATED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final ThreadLocal<PhaseClock> CLOCKS = ThreadLocal.withInitial(() -> {
        PHASE_CLOCKS_CREATED.incrementAndGet();
        return new PhaseClock();
    });

    private static volatile boolean enabled;
    private static volatile boolean detailed;
    private static volatile LongSupplier clock = System::nanoTime;
    private static volatile long lagTickThresholdNs = lagThresholdFromProperty();

    private static int jsonFilesWritten;

    private static final java.util.concurrent.atomic.AtomicLong[] ACCUM = new java.util.concurrent.atomic.AtomicLong[METRIC_COUNT];
    private static final long[] LAST = new long[METRIC_COUNT];
    private static final SampleRing[] RINGS = new SampleRing[METRIC_COUNT];

    private static long tickNumber;
    private static int playerCount;
    private static int eventCount;
    private static final SemanticEventKind[] EVENT_KINDS = new SemanticEventKind[MAX_EVENTS_PER_TICK];
    private static final String[] EVENT_MESSAGES = new String[MAX_EVENTS_PER_TICK];
    private static int playerSlotCount;
    private static final String[] PLAYER_NAMES = new String[MAX_PLAYERS_TRACKED];
    private static final int[] PLAYER_NEW_CHUNKS = new int[MAX_PLAYERS_TRACKED];

    private static ServerProfileSession session;

    static {
        for (int i = 0; i < METRIC_COUNT; i++) {
            ACCUM[i] = new java.util.concurrent.atomic.AtomicLong();
            RINGS[i] = new SampleRing(DEFAULT_RING_SIZE);
        }
    }

    private ServerMetrics() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isDetailed() {
        return enabled && detailed;
    }

    public static void setEnabled(final boolean value) {
        enabled = value;
        if (!value) {
            detailed = false;
            session = null;
        }
    }

    public static void setClock(final LongSupplier value) {
        clock = value == null ? System::nanoTime : value;
    }

    public static void setLagTickThresholdNs(final long nanos) {
        lagTickThresholdNs = Math.max(0L, nanos);
    }

    public static long lagTickThresholdNs() {
        return lagTickThresholdNs;
    }

    public static int phaseClocksCreated() {
        return PHASE_CLOCKS_CREATED.get();
    }

    public static int jsonFilesWritten() {
        return jsonFilesWritten;
    }

    public static void markJsonWritten() {
        jsonFilesWritten++;
    }

    /**
     * Test harness: replace clock/rings and drop any leftover profile. Does not allocate on the
     * subsequent disabled hot path.
     */
    public static void resetForTest(final boolean enable, final LongSupplier testClock, final int ringSize) {
        enabled = enable;
        detailed = false;
        session = null;
        clock = testClock == null ? System::nanoTime : testClock;
        PHASE_CLOCKS_CREATED.set(0);
        jsonFilesWritten = 0;
        tickNumber = 0L;
        playerCount = 0;
        eventCount = 0;
        playerSlotCount = 0;
        CLOCKS.remove();
        for (int i = 0; i < METRIC_COUNT; i++) {
            ACCUM[i].set(0L);
            LAST[i] = 0L;
            RINGS[i] = new SampleRing(Math.max(1, ringSize));
        }
    }

    public static void beginTick(final long tick, final int players) {
        if (!enabled) {
            return;
        }
        tickNumber = tick;
        playerCount = players;
        CLOCKS.get().reset();
    }

    public static void endTick() {
        if (!enabled) {
            return;
        }
        CLOCKS.get().closeAll(clock.getAsLong(), ACCUM);
        for (int i = 0; i < METRIC_COUNT; i++) {
            long value = ACCUM[i].getAndSet(0L);
            LAST[i] = value;
            RINGS[i].add(value);
        }
        if (detailed) {
            appendGeneratedAndPacketEvents();
            ServerProfileSession active = session;
            if (active != null) {
                active.recordTick(copyTickRecord());
            }
        }
        eventCount = 0;
        playerSlotCount = 0;
        if (lagTickThresholdNs > 0L && LAST[MetricId.TICK_TOTAL.ordinal()] > lagTickThresholdNs) {
            ServerProfileSession active = session;
            if (active != null) {
                active.recordLagTick();
            }
            logLagTick();
        }
        ServerProfileSession active = session;
        if (active != null && active.shouldStop(clock.getAsLong())) {
            finishProfile(active, "completed");
        }
    }

    static void notify(final String line) {
        ServerProfileSession active = session;
        if (active == null) {
            return;
        }
        Consumer<String> sink = active.feedback();
        if (sink != null) {
            sink.accept(line);
        }
    }

    public static void begin(final MetricId metric) {
        if (!enabled || metric.kind() != MetricId.Kind.TIME) {
            return;
        }
        CLOCKS.get().begin(metric.ordinal(), clock.getAsLong());
    }

    public static void end(final MetricId metric) {
        if (!enabled || metric.kind() != MetricId.Kind.TIME) {
            return;
        }
        CLOCKS.get().end(metric.ordinal(), clock.getAsLong(), ACCUM);
    }

    public static void addCount(final MetricId metric, final long delta) {
        if (!enabled || delta == 0L) {
            return;
        }
        ACCUM[metric.ordinal()].addAndGet(delta);
    }

    public static void setGauge(final MetricId metric, final long value) {
        if (!enabled) {
            return;
        }
        ACCUM[metric.ordinal()].set(value);
    }

    public static void event(final SemanticEventKind kind) {
        event(kind, kind.defaultMessage());
    }

    public static void event(final SemanticEventKind kind, final String message) {
        if (!isDetailed() || eventCount >= MAX_EVENTS_PER_TICK) {
            return;
        }
        EVENT_KINDS[eventCount] = kind;
        EVENT_MESSAGES[eventCount] = message == null ? "" : message;
        eventCount++;
    }

    public static void recordPlayerEnteredChunk(final String playerName) {
        if (!enabled) {
            return;
        }
        ACCUM[MetricId.PLAYER_CHUNKS_ENTERED.ordinal()].incrementAndGet();
        if (!detailed || playerName == null || playerName.isEmpty()) {
            return;
        }
        for (int i = 0; i < playerSlotCount; i++) {
            if (playerName.equals(PLAYER_NAMES[i])) {
                PLAYER_NEW_CHUNKS[i]++;
                return;
            }
        }
        if (playerSlotCount < MAX_PLAYERS_TRACKED) {
            int slot = playerSlotCount++;
            PLAYER_NAMES[slot] = playerName;
            PLAYER_NEW_CHUNKS[slot] = 1;
        }
    }

    public static long last(final MetricId metric) {
        return LAST[metric.ordinal()];
    }

    public static SampleRing ring(final MetricId metric) {
        return RINGS[metric.ordinal()];
    }

    public static long tickNumber() {
        return tickNumber;
    }

    public static int playerCount() {
        return playerCount;
    }

    public static boolean isProfiling() {
        return session != null && session.isActive();
    }

    public static int clampProfileSeconds(final int seconds) {
        if (seconds < 1) {
            return DEFAULT_PROFILE_SECONDS;
        }
        return Math.min(seconds, MAX_PROFILE_SECONDS);
    }

    public static String startProfile(final int requestedSeconds, final Consumer<String> feedback, final java.nio.file.Path outputFile) {
        if (!enabled) {
            return "server_metrics is disabled; enable it in ultima.properties and restart before profiling.";
        }
        int seconds = clampProfileSeconds(requestedSeconds);
        if (session != null && session.isActive()) {
            finishProfile(session, "superseded");
        }
        for (SampleRing ring : RINGS) {
            ring.clear();
        }
        detailed = true;
        long now = clock.getAsLong();
        session = new ServerProfileSession(seconds, now, feedback, outputFile);
        String message = "Ultima profile started for " + seconds + "s (max " + MAX_PROFILE_SECONDS
                + "s). Detailed per-tick tracing is on until the timer ends.";
        notify(message);
        return message;
    }

    public static String stopProfile() {
        ServerProfileSession active = session;
        if (active == null || !active.isActive()) {
            return "No Ultima profile is running.";
        }
        return finishProfile(active, "stopped");
    }

    static ServerProfileSession session() {
        return session;
    }

    public static List<MetricSnapshot> snapshots() {
        List<MetricSnapshot> snapshots = new ArrayList<>(METRIC_COUNT);
        for (MetricId metric : MetricId.all()) {
            snapshots.add(snapshotOf(metric));
        }
        return snapshots;
    }

    public static MetricSnapshot snapshotOf(final MetricId metric) {
        SampleRing ring = RINGS[metric.ordinal()];
        Long p50 = null;
        Long p95 = null;
        Long p99 = null;
        Long max = null;
        if (metric.histogram() && ring.size() > 0) {
            long[] sorted = ring.copy();
            java.util.Arrays.sort(sorted);
            p50 = SampleRing.percentile(sorted, 0.50);
            p95 = SampleRing.percentile(sorted, 0.95);
            p99 = SampleRing.percentile(sorted, 0.99);
            max = sorted[sorted.length - 1];
        }
        return new MetricSnapshot(
                metric.id(),
                metric.kind().name().toLowerCase(Locale.ROOT),
                metric.histogram(),
                LAST[metric.ordinal()],
                ring.sum(),
                ring.size(),
                ring.average(),
                p50,
                p95,
                p99,
                max);
    }

    private static String finishProfile(final ServerProfileSession active, final String reason) {
        detailed = false;
        active.close(clock.getAsLong(), snapshots(), reason);
        String summary = active.summary();
        Consumer<String> sink = active.feedback();
        if (sink != null) {
            sink.accept(summary);
        }
        java.nio.file.Path written = ServerProfileJson.write(active);
        if (written != null) {
            jsonFilesWritten++;
            String jsonLine = "JSON: " + written.toAbsolutePath();
            if (sink != null) {
                sink.accept(jsonLine);
            }
            summary = summary + "\n" + jsonLine;
        }
        session = null;
        return summary;
    }

    private static void appendGeneratedAndPacketEvents() {
        long generated = LAST[MetricId.CHUNKS_GENERATED.ordinal()];
        if (generated > 0L) {
            event(SemanticEventKind.CHUNKS_GENERATED, generated + " chunks generated");
        }
        long bytes = LAST[MetricId.PACKETS_PREPARED_BYTES.ordinal()];
        if (bytes > 0L) {
            event(SemanticEventKind.PACKETS_PREPARED, formatMib(bytes) + " packets prepared");
        }
        for (int i = 0; i < playerSlotCount; i++) {
            event(
                    SemanticEventKind.PLAYER_ENTERED_CHUNKS,
                    "player " + PLAYER_NAMES[i] + " moved into " + PLAYER_NEW_CHUNKS[i] + " new chunks");
        }
    }

    private static TickRecord copyTickRecord() {
        long[] values = new long[METRIC_COUNT];
        System.arraycopy(LAST, 0, values, 0, METRIC_COUNT);
        List<SemanticEvent> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            SemanticEventKind kind = EVENT_KINDS[i];
            events.add(new SemanticEvent(tickNumber, kind == null ? "" : kind.id(), EVENT_MESSAGES[i]));
        }
        return new TickRecord(tickNumber, playerCount, values, List.copyOf(events));
    }

    private static void logLagTick() {
        long totalNs = LAST[MetricId.TICK_TOTAL.ordinal()];
        org.slf4j.LoggerFactory.getLogger("ultima-server-metrics")
                .info(
                        "Lag tick #{} {} ms (threshold {} ms): entities={}ms be={}ms ai={}ms worldgen={}ms send_prepare={}ms",
                        tickNumber,
                        String.format(Locale.ROOT, "%.2f", totalNs / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", lagTickThresholdNs / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", LAST[MetricId.TICK_ENTITIES.ordinal()] / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", LAST[MetricId.TICK_BLOCK_ENTITIES.ordinal()] / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", LAST[MetricId.TICK_AI.ordinal()] / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", LAST[MetricId.CHUNK_GENERATE.ordinal()] / 1_000_000.0),
                        String.format(Locale.ROOT, "%.2f", LAST[MetricId.CHUNK_SEND_PREPARE.ordinal()] / 1_000_000.0));
    }

    static String formatMib(final long bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    static long lagThresholdFromProperty() {
        String value = System.getProperty(LAG_TICK_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_LAG_TICK_NS;
        }
        try {
            double ms = Double.parseDouble(value.trim());
            if (ms <= 0.0) {
                return 0L;
            }
            return (long)(ms * 1_000_000.0);
        } catch (NumberFormatException ignored) {
            return DEFAULT_LAG_TICK_NS;
        }
    }

    public record MetricSnapshot(
            String id,
            String kind,
            boolean histogram,
            long last,
            long windowSum,
            int windowCount,
            double windowAvg,
            Long p50,
            Long p95,
            Long p99,
            Long max) {
    }

    public record TickRecord(long tick, int playerCount, long[] values, List<SemanticEvent> events) {
        public TickRecord {
            values = values.clone();
            events = List.copyOf(events);
        }

        public long value(final MetricId metric) {
            return values[metric.ordinal()];
        }
    }

    static final class PhaseClock {
        private final long[] startNs = new long[METRIC_COUNT];
        private final int[] depth = new int[METRIC_COUNT];

        void reset() {
            for (int i = 0; i < METRIC_COUNT; i++) {
                this.depth[i] = 0;
                this.startNs[i] = 0L;
            }
        }

        void begin(final int ordinal, final long now) {
            if (this.depth[ordinal]++ == 0) {
                this.startNs[ordinal] = now;
            }
        }

        void end(final int ordinal, final long now, final java.util.concurrent.atomic.AtomicLong[] accum) {
            int next = this.depth[ordinal] - 1;
            if (next < 0) {
                this.depth[ordinal] = 0;
                this.startNs[ordinal] = 0L;
                return;
            }
            this.depth[ordinal] = next;
            if (next == 0 && this.startNs[ordinal] != 0L) {
                long dt = now - this.startNs[ordinal];
                if (dt < 0L) {
                    dt = 0L;
                }
                accum[ordinal].addAndGet(dt);
                this.startNs[ordinal] = 0L;
            }
        }

        void closeAll(final long now, final java.util.concurrent.atomic.AtomicLong[] accum) {
            for (int i = 0; i < METRIC_COUNT; i++) {
                while (this.depth[i] > 0) {
                    this.end(i, now, accum);
                }
            }
        }
    }

    static int maxDetailedTicks() {
        return MAX_DETAILED_TICKS;
    }
}
