package dev.ultima.metrics;

import java.util.function.LongSupplier;

/**
 * Nested, balanced CPU phase clock for terrain A/B.
 *
 * <p>{@link #totalCpuNs()} is {@code prepare + opaque submit + translucent
 * submit}. Command generation is a diagnostic subset of prepare on the retained
 * path and is never added into the total.
 *
 * <p>Nested begin/end of the same phase (mixin wrapper + retained self-time)
 * counts once. Sequential intervals of the same phase (failed retained attempt
 * then vanilla fallback) sum. {@link #beginFrame()} drops previous-frame values.
 */
public final class TerrainCpuPhases {
    public enum Phase {
        PREPARE,
        COMMAND,
        OPAQUE_SUBMIT,
        TRANSLUCENT_SUBMIT
    }

    private static final int N = Phase.values().length;

    private final LongSupplier clock;
    private final long[] startNs = new long[N];
    private final int[] depth = new int[N];
    private final long[] lastNs = new long[N];
    private final long[] accumNs = new long[N];
    private int begins;
    private int ends;
    private int pairingErrors;

    public TerrainCpuPhases() {
        this(System::nanoTime);
    }

    public TerrainCpuPhases(final LongSupplier clock) {
        this.clock = clock;
    }

    public void beginFrame() {
        for (int i = 0; i < N; i++) {
            if (this.depth[i] != 0) {
                this.pairingErrors++;
                this.depth[i] = 0;
                this.startNs[i] = 0L;
            }
            this.lastNs[i] = 0L;
            this.accumNs[i] = 0L;
        }
        this.begins = 0;
        this.ends = 0;
    }

    public void begin(final Phase phase) {
        this.begins++;
        int ord = phase.ordinal();
        if (this.depth[ord]++ == 0) {
            this.startNs[ord] = this.clock.getAsLong();
        }
    }

    public void end(final Phase phase) {
        this.ends++;
        int ord = phase.ordinal();
        int next = this.depth[ord] - 1;
        if (next < 0) {
            this.pairingErrors++;
            this.depth[ord] = 0;
            this.startNs[ord] = 0L;
            return;
        }
        this.depth[ord] = next;
        if (next == 0 && this.startNs[ord] != 0L) {
            long dt = this.clock.getAsLong() - this.startNs[ord];
            if (dt < 0L) {
                dt = 0L;
            }
            this.lastNs[ord] = dt;
            this.accumNs[ord] += dt;
            this.startNs[ord] = 0L;
        }
    }

    public long lastNs(final Phase phase) {
        return this.lastNs[phase.ordinal()];
    }

    public long accumNs(final Phase phase) {
        return this.accumNs[phase.ordinal()];
    }

    public long totalCpuNs() {
        return this.accumNs[Phase.PREPARE.ordinal()]
                + this.accumNs[Phase.OPAQUE_SUBMIT.ordinal()]
                + this.accumNs[Phase.TRANSLUCENT_SUBMIT.ordinal()];
    }

    public boolean pairingBalanced() {
        if (this.begins != this.ends || this.pairingErrors != 0) {
            return false;
        }
        for (int d : this.depth) {
            if (d != 0) {
                return false;
            }
        }
        return true;
    }

    public int begins() {
        return this.begins;
    }

    public int ends() {
        return this.ends;
    }

    public int pairingErrors() {
        return this.pairingErrors;
    }

    public int depth(final Phase phase) {
        return this.depth[phase.ordinal()];
    }
}
