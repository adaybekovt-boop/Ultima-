package dev.ultima.server.metrics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bounded opt-in detailed trace. Inactive unless {@link ServerMetrics#startProfile} is called.
 */
public final class ServerProfileSession {
    private final int requestedSeconds;
    private final long startedAtNs;
    private final long endAtNs;
    private final long startedAtEpochMs;
    private final Consumer<String> feedback;
    private final Path outputFile;
    private final List<ServerMetrics.TickRecord> ticks = new ArrayList<>();
    private int lagTicks;
    private boolean active = true;
    private long endedAtNs;
    private long endedAtEpochMs;
    private String stopReason = "";
    private List<ServerMetrics.MetricSnapshot> snapshots = List.of();

    ServerProfileSession(
            final int requestedSeconds,
            final long startedAtNs,
            final Consumer<String> feedback,
            final Path outputFile) {
        this.requestedSeconds = requestedSeconds;
        this.startedAtNs = startedAtNs;
        this.endAtNs = startedAtNs + TimeUnit.SECONDS.toNanos(requestedSeconds);
        this.startedAtEpochMs = System.currentTimeMillis();
        this.feedback = feedback;
        this.outputFile = outputFile;
    }

    public boolean isActive() {
        return this.active;
    }

    public int requestedSeconds() {
        return this.requestedSeconds;
    }

    public Consumer<String> feedback() {
        return this.feedback;
    }

    public Path outputFile() {
        return this.outputFile;
    }

    public List<ServerMetrics.TickRecord> ticks() {
        return this.ticks;
    }

    public int lagTicks() {
        return this.lagTicks;
    }

    public String stopReason() {
        return this.stopReason;
    }

    public long startedAtEpochMs() {
        return this.startedAtEpochMs;
    }

    public long endedAtEpochMs() {
        return this.endedAtEpochMs;
    }

    public List<ServerMetrics.MetricSnapshot> snapshots() {
        return this.snapshots;
    }

    boolean shouldStop(final long nowNs) {
        return !this.active
                || nowNs >= this.endAtNs
                || this.ticks.size() >= ServerMetrics.maxDetailedTicks();
    }

    void recordTick(final ServerMetrics.TickRecord record) {
        if (!this.active || this.ticks.size() >= ServerMetrics.maxDetailedTicks()) {
            return;
        }
        this.ticks.add(record);
    }

    void recordLagTick() {
        if (this.active) {
            this.lagTicks++;
        }
    }

    void close(final long nowNs, final List<ServerMetrics.MetricSnapshot> snapshots, final String reason) {
        this.active = false;
        this.endedAtNs = nowNs;
        this.endedAtEpochMs = System.currentTimeMillis();
        this.snapshots = List.copyOf(snapshots);
        this.stopReason = reason;
    }

    public String summary() {
        ServerMetrics.MetricSnapshot total = snapshot("tick.total");
        String p95 = total == null || total.p95() == null ? "n/a" : formatMs(total.p95());
        String p99 = total == null || total.p99() == null ? "n/a" : formatMs(total.p99());
        String max = total == null || total.max() == null ? "n/a" : formatMs(total.max());
        String p50 = total == null || total.p50() == null ? "n/a" : formatMs(total.p50());
        StringBuilder text = new StringBuilder(256);
        text.append("Ultima profile ")
                .append(this.stopReason)
                .append(": ")
                .append(this.requestedSeconds)
                .append("s requested, ")
                .append(this.ticks.size())
                .append(" ticks, tick.total p50=")
                .append(p50)
                .append(" p95=")
                .append(p95)
                .append(" p99=")
                .append(p99)
                .append(" max=")
                .append(max)
                .append(". Lag ticks >")
                .append(formatMs(ServerMetrics.lagTickThresholdNs()))
                .append(": ")
                .append(this.lagTicks)
                .append('.');
        List<ServerMetrics.MetricSnapshot> top = topTimeSubsystems(5);
        if (!top.isEmpty()) {
            text.append(" Top subsystems:");
            for (ServerMetrics.MetricSnapshot snapshot : top) {
                text.append(' ')
                        .append(snapshot.id())
                        .append('=')
                        .append(formatMs(snapshot.windowSum()));
            }
            text.append('.');
        }
        return text.toString();
    }

    List<ServerMetrics.MetricSnapshot> topTimeSubsystems(final int limit) {
        List<ServerMetrics.MetricSnapshot> times = new ArrayList<>();
        for (ServerMetrics.MetricSnapshot snapshot : this.snapshots) {
            if ("time".equals(snapshot.kind()) && !"tick.total".equals(snapshot.id()) && snapshot.windowSum() > 0L) {
                times.add(snapshot);
            }
        }
        times.sort(Comparator.comparingLong(ServerMetrics.MetricSnapshot::windowSum).reversed());
        if (times.size() > limit) {
            return List.copyOf(times.subList(0, limit));
        }
        return List.copyOf(times);
    }

    private ServerMetrics.MetricSnapshot snapshot(final String id) {
        for (ServerMetrics.MetricSnapshot snapshot : this.snapshots) {
            if (snapshot.id().equals(id)) {
                return snapshot;
            }
        }
        return null;
    }

    static String formatMs(final long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    long elapsedNs() {
        long end = this.endedAtNs == 0L ? this.startedAtNs : this.endedAtNs;
        return Math.max(0L, end - this.startedAtNs);
    }
}
