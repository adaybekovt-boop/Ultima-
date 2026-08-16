package dev.ultima.util;

import java.util.BitSet;

/**
 * Coalesces set bits into half-open {@code [from, to)} runs.
 * Used for dirty-range GPU uploads.
 */
public final class BitSetRuns {
    private BitSetRuns() {
    }

    @FunctionalInterface
    public interface RunConsumer {
        void accept(int fromInclusive, int toExclusive);
    }

    public static int forEachRun(final BitSet bits, final int limit, final RunConsumer consumer) {
        int runs = 0;
        int from = bits.nextSetBit(0);
        while (from >= 0 && from < limit) {
            int to = bits.nextClearBit(from);
            if (to > limit) {
                to = limit;
            }
            consumer.accept(from, to);
            runs++;
            from = bits.nextSetBit(to);
        }
        return runs;
    }
}
