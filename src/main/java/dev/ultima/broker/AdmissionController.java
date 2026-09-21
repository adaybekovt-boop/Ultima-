package dev.ultima.broker;

import java.util.Arrays;
import java.util.Locale;

/**
 * One-sided admission controller. It never owns, dequeues, cancels, or executes work; it only
 * decides whether the owner may enter its existing low-priority dequeue loop this frame.
 */
public final class AdmissionController {
    private static final int WINDOW = 128;
    private static final int RECOMPUTE_INTERVAL = 8;

    public enum Mode {
        TRACE,
        CONTROL,
        STATIC;

        public static Mode parse(final String value) {
            if (value == null) {
                return TRACE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "control" -> CONTROL;
                case "static" -> STATIC;
                default -> TRACE;
            };
        }
    }

    public record Config(
            long targetFrameNanos,
            long staleTelemetryNanos,
            long minimumPermitIntervalNanos,
            long maximumDeferNanos,
            int staticPermitEveryFrames) {
        public Config {
            targetFrameNanos = Math.max(1_000_000L, targetFrameNanos);
            staleTelemetryNanos = Math.max(targetFrameNanos, staleTelemetryNanos);
            minimumPermitIntervalNanos = Math.max(0L, minimumPermitIntervalNanos);
            maximumDeferNanos = Math.max(minimumPermitIntervalNanos, maximumDeferNanos);
            staticPermitEveryFrames = Math.max(1, staticPermitEveryFrames);
        }

        public static Config defaults() {
            return new Config(16_666_667L, 500_000_000L, 50_000_000L, 250_000_000L, 2);
        }
    }

    public record Snapshot(
            Mode mode,
            boolean pressureHigh,
            int samples,
            long latestFrameNanos,
            long p95FrameNanos,
            long p99FrameNanos,
            long p999FrameNanos,
            long latestGpuNanos,
            long integratedServerTickNanos,
            int queueDepth,
            int busyWorkers,
            int totalWorkers,
            long maximumObservedDeferredNanos) {
    }

    private final Mode mode;
    private final Config config;
    private final long[] frameWindow = new long[WINDOW];
    private final long[] sortScratch = new long[WINDOW];
    private int frameCursor;
    private int frameSamples;
    private int framesSincePercentiles;
    private long latestFrameNanos;
    private long p95FrameNanos;
    private long p99FrameNanos;
    private long p999FrameNanos;
    private long latestGpuNanos = -1L;
    private long integratedServerTickNanos = -1L;
    private long telemetryAtNanos = Long.MIN_VALUE;
    private boolean pressureHigh;
    private int queueDepth;
    private int busyWorkers;
    private int totalWorkers;
    private long deferredSinceNanos = Long.MIN_VALUE;
    private long lastPermitNanos = Long.MIN_VALUE;
    private long maximumObservedDeferredNanos;
    private long staticFrame;

    public AdmissionController(final Mode mode, final Config config) {
        this.mode = mode == null ? Mode.TRACE : mode;
        this.config = config == null ? Config.defaults() : config;
    }

    /** Render-thread frame telemetry. GPU/server values are {@code -1} when unavailable. */
    public void onFrame(
            final long nowNanos,
            final long frameNanos,
            final long gpuNanos,
            final long serverTickNanos) {
        if (frameNanos <= 0L) {
            return;
        }
        this.latestFrameNanos = frameNanos;
        this.latestGpuNanos = gpuNanos;
        this.integratedServerTickNanos = serverTickNanos;
        this.telemetryAtNanos = nowNanos;
        this.frameWindow[this.frameCursor] = frameNanos;
        this.frameCursor = (this.frameCursor + 1) & (WINDOW - 1);
        this.frameSamples = Math.min(WINDOW, this.frameSamples + 1);
        if (++this.framesSincePercentiles >= RECOMPUTE_INTERVAL || this.frameSamples == 1) {
            this.framesSincePercentiles = 0;
            recomputePercentiles();
        }
        updateHysteresis();
    }

    public void onPipelinePressure(final int queueDepth, final int busyWorkers, final int totalWorkers) {
        this.queueDepth = Math.max(0, queueDepth);
        this.busyWorkers = Math.max(0, busyWorkers);
        this.totalWorkers = Math.max(0, totalWorkers);
    }

    /**
     * @param urgent true for sync/update-immediately paths; these always bypass the broker
     * @return true when the owner should execute its original deferred submission method
     */
    public boolean permit(final long nowNanos, final boolean urgent) {
        if (urgent || this.mode == Mode.TRACE || this.queueDepth <= 0) {
            markPermit(nowNanos);
            return true;
        }
        if (this.telemetryAtNanos == Long.MIN_VALUE
                || nowNanos - this.telemetryAtNanos > this.config.staleTelemetryNanos()) {
            markPermit(nowNanos);
            return true;
        }

        if (this.mode == Mode.STATIC) {
            boolean permit = this.staticFrame++ % this.config.staticPermitEveryFrames() == 0L;
            if (permit) {
                markPermit(nowNanos);
            } else {
                markDeferred(nowNanos);
            }
            return permit;
        }

        if (!this.pressureHigh) {
            markPermit(nowNanos);
            return true;
        }

        long deferredFor = this.deferredSinceNanos == Long.MIN_VALUE ? 0L : nowNanos - this.deferredSinceNanos;
        long sincePermit = this.lastPermitNanos == Long.MIN_VALUE ? Long.MAX_VALUE : nowNanos - this.lastPermitNanos;
        if (deferredFor >= this.config.maximumDeferNanos()
                || sincePermit >= this.config.maximumDeferNanos()) {
            markPermit(nowNanos);
            return true;
        }
        if (sincePermit >= this.config.minimumPermitIntervalNanos()) {
            markPermit(nowNanos);
            return true;
        }

        markDeferred(nowNanos);
        return false;
    }

    public void reset() {
        Arrays.fill(this.frameWindow, 0L);
        this.frameCursor = 0;
        this.frameSamples = 0;
        this.framesSincePercentiles = 0;
        this.latestFrameNanos = 0L;
        this.p95FrameNanos = 0L;
        this.p99FrameNanos = 0L;
        this.p999FrameNanos = 0L;
        this.latestGpuNanos = -1L;
        this.integratedServerTickNanos = -1L;
        this.telemetryAtNanos = Long.MIN_VALUE;
        this.pressureHigh = false;
        this.queueDepth = 0;
        this.busyWorkers = 0;
        this.totalWorkers = 0;
        this.deferredSinceNanos = Long.MIN_VALUE;
        this.lastPermitNanos = Long.MIN_VALUE;
        this.maximumObservedDeferredNanos = 0L;
        this.staticFrame = 0L;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.mode,
                this.pressureHigh,
                this.frameSamples,
                this.latestFrameNanos,
                this.p95FrameNanos,
                this.p99FrameNanos,
                this.p999FrameNanos,
                this.latestGpuNanos,
                this.integratedServerTickNanos,
                this.queueDepth,
                this.busyWorkers,
                this.totalWorkers,
                this.maximumObservedDeferredNanos);
    }

    private void updateHysteresis() {
        long high = this.config.targetFrameNanos() * 108L / 100L;
        long low = this.config.targetFrameNanos() * 92L / 100L;
        boolean gpuHigh = this.latestGpuNanos > 0L && this.latestGpuNanos > high;
        boolean serverHigh = this.integratedServerTickNanos > 45_000_000L;
        boolean severeLatest = this.latestFrameNanos > this.config.targetFrameNanos() * 3L / 2L;
        if (!this.pressureHigh) {
            this.pressureHigh = this.p95FrameNanos > high || gpuHigh || severeLatest || serverHigh;
        } else {
            boolean gpuLow = this.latestGpuNanos <= 0L || this.latestGpuNanos < low;
            boolean serverLow = this.integratedServerTickNanos <= 0L || this.integratedServerTickNanos < 40_000_000L;
            if (this.p95FrameNanos < low && gpuLow && serverLow) {
                this.pressureHigh = false;
            }
        }
    }

    private void recomputePercentiles() {
        System.arraycopy(this.frameWindow, 0, this.sortScratch, 0, this.frameSamples);
        Arrays.sort(this.sortScratch, 0, this.frameSamples);
        this.p95FrameNanos = percentile(this.sortScratch, this.frameSamples, 0.95);
        this.p99FrameNanos = percentile(this.sortScratch, this.frameSamples, 0.99);
        this.p999FrameNanos = percentile(this.sortScratch, this.frameSamples, 0.999);
    }

    private void markPermit(final long nowNanos) {
        if (this.deferredSinceNanos != Long.MIN_VALUE) {
            this.maximumObservedDeferredNanos = Math.max(
                    this.maximumObservedDeferredNanos, nowNanos - this.deferredSinceNanos);
        }
        this.deferredSinceNanos = Long.MIN_VALUE;
        this.lastPermitNanos = nowNanos;
    }

    private void markDeferred(final long nowNanos) {
        if (this.deferredSinceNanos == Long.MIN_VALUE) {
            this.deferredSinceNanos = nowNanos;
        } else {
            this.maximumObservedDeferredNanos = Math.max(
                    this.maximumObservedDeferredNanos, nowNanos - this.deferredSinceNanos);
        }
    }

    private static long percentile(final long[] sorted, final int length, final double percentile) {
        if (length <= 0) {
            return 0L;
        }
        int index = Math.max(0, Math.min(length - 1, (int)Math.ceil(percentile * length) - 1));
        return sorted[index];
    }
}
