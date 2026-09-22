package dev.ultima.client.benchmark;

import java.util.Arrays;

/** Primitive, allocation-free-per-sample buffer used by tick-duration benchmarks. */
final class LongSampleBuffer {
    private long[] values;
    private int size;

    LongSampleBuffer(final int initialCapacity) {
        this.values = new long[Math.max(16, initialCapacity)];
    }

    void add(final long value) {
        if (this.size == this.values.length) {
            int next = Math.max(this.values.length + 1, this.values.length << 1);
            this.values = Arrays.copyOf(this.values, next);
        }
        this.values[this.size++] = value;
    }

    int size() {
        return this.size;
    }

    long[] toArray() {
        return Arrays.copyOf(this.values, this.size);
    }
}
