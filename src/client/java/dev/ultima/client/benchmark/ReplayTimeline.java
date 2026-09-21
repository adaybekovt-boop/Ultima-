package dev.ultima.client.benchmark;

import java.util.Locale;

/**
 * Clock for reproducible client benchmark routes.
 *
 * <p>The primary mode is tick based: every rendered frame in the same game tick observes the same
 * route position, regardless of FPS. The legacy frame-indexed mode remains available only for
 * diagnostics and intentionally preserves the old FPS-dependent behavior.
 */
public final class ReplayTimeline {
    public enum Mode {
        TICK("tick"),
        FRAME("frame");

        private final String key;

        Mode(final String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        public static Mode parse(final String value) {
            if (value != null && "frame".equals(value.trim().toLowerCase(Locale.ROOT))) {
                return FRAME;
            }
            return TICK;
        }
    }

    public record State(long routeUnit, boolean warmup, boolean sampling, boolean complete) {
    }

    private final Mode mode;
    private final long warmupUnits;
    private final long sampleUnits;
    private long firstGameTick = Long.MIN_VALUE;
    private long framesSeen;

    public ReplayTimeline(final Mode mode, final long warmupUnits, final long sampleUnits) {
        if (warmupUnits < 0L || sampleUnits <= 0L) {
            throw new IllegalArgumentException("warmup must be non-negative and sample duration must be positive");
        }
        this.mode = mode == null ? Mode.TICK : mode;
        this.warmupUnits = warmupUnits;
        this.sampleUnits = sampleUnits;
    }

    public State advance(final long gameTick) {
        long routeUnit;
        if (this.mode == Mode.TICK) {
            if (this.firstGameTick == Long.MIN_VALUE || gameTick < this.firstGameTick) {
                this.firstGameTick = gameTick;
            }
            routeUnit = Math.max(0L, gameTick - this.firstGameTick);
        } else {
            routeUnit = this.framesSeen;
        }
        this.framesSeen++;

        boolean warmup = routeUnit < this.warmupUnits;
        boolean complete = routeUnit >= saturatedAdd(this.warmupUnits, this.sampleUnits);
        return new State(routeUnit, warmup, !warmup && !complete, complete);
    }

    public void reset() {
        this.firstGameTick = Long.MIN_VALUE;
        this.framesSeen = 0L;
    }

    public Mode mode() {
        return this.mode;
    }

    public long warmupUnits() {
        return this.warmupUnits;
    }

    public long sampleUnits() {
        return this.sampleUnits;
    }

    private static long saturatedAdd(final long left, final long right) {
        long result = left + right;
        return result < 0L ? Long.MAX_VALUE : result;
    }
}
