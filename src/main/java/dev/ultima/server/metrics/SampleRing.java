package dev.ultima.server.metrics;

import java.util.Arrays;

/**
 * Fixed-capacity ring of {@code long} samples. Percentiles are computed on demand from a sorted
 * copy; the hot path only writes one slot.
 */
public final class SampleRing {
    private final long[] values;
    private int next;
    private int size;
    private long sum;

    public SampleRing(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.values = new long[capacity];
    }

    public void add(final long value) {
        if (this.size == this.values.length) {
            this.sum -= this.values[this.next];
        }
        this.values[this.next] = value;
        this.sum += value;
        this.next++;
        if (this.next == this.values.length) {
            this.next = 0;
        }
        if (this.size < this.values.length) {
            this.size++;
        }
    }

    public void clear() {
        this.next = 0;
        this.size = 0;
        this.sum = 0L;
    }

    public int size() {
        return this.size;
    }

    public int capacity() {
        return this.values.length;
    }

    public long sum() {
        return this.sum;
    }

    public double average() {
        return this.size == 0 ? 0.0 : (double)this.sum / (double)this.size;
    }

    public long last() {
        if (this.size == 0) {
            return 0L;
        }
        int index = this.next - 1;
        if (index < 0) {
            index = this.values.length - 1;
        }
        return this.values[index];
    }

    public long max() {
        if (this.size == 0) {
            return 0L;
        }
        long max = Long.MIN_VALUE;
        for (int i = 0; i < this.size; i++) {
            long value = this.valueAt(i);
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    /**
     * Nearest-rank percentile matching the client benchmark recorder:
     * {@code sorted[ceil(p * n) - 1]}.
     */
    public long percentile(final double percentile) {
        if (this.size == 0) {
            return 0L;
        }
        long[] sorted = this.copy();
        Arrays.sort(sorted);
        return percentile(sorted, percentile);
    }

    public static long percentile(final long[] sorted, final double percentile) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = Math.max(0, (int)Math.ceil(percentile * sorted.length) - 1);
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    public long[] copy() {
        long[] copy = new long[this.size];
        for (int i = 0; i < this.size; i++) {
            copy[i] = this.valueAt(i);
        }
        return copy;
    }

    private long valueAt(final int chronologicalIndex) {
        if (this.size < this.values.length) {
            return this.values[chronologicalIndex];
        }
        int index = this.next + chronologicalIndex;
        if (index >= this.values.length) {
            index -= this.values.length;
        }
        return this.values[index];
    }
}
