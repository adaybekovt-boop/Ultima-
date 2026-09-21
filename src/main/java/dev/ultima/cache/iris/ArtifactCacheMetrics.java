package dev.ultima.cache.iris;

import java.util.concurrent.atomic.LongAdder;

/** Contention-friendly counters; this path runs during shader loading, never per block or draw. */
public final class ArtifactCacheMetrics {
    final LongAdder requests = new LongAdder();
    final LongAdder hits = new LongAdder();
    final LongAdder misses = new LongAdder();
    final LongAdder invalidations = new LongAdder();
    final LongAdder corruptions = new LongAdder();
    final LongAdder readFailures = new LongAdder();
    final LongAdder writeFailures = new LongAdder();
    final LongAdder transformNanosSaved = new LongAdder();
    final LongAdder cacheReadNanos = new LongAdder();
    final LongAdder cacheWriteNanos = new LongAdder();
    final LongAdder bytesRead = new LongAdder();
    final LongAdder bytesWritten = new LongAdder();
    final LongAdder frontendTransformNanos = new LongAdder();
    final LongAdder verifyMatches = new LongAdder();
    final LongAdder verifyMismatches = new LongAdder();

    public void recordUnkeyableRequest() {
        this.requests.increment();
        this.misses.increment();
    }

    public void recordFrontendTransform(final long nanos) {
        this.frontendTransformNanos.add(Math.max(0L, nanos));
    }

    public void recordVerifyMatch() {
        this.verifyMatches.increment();
    }

    public void recordVerifyMismatch() {
        this.verifyMismatches.increment();
    }

    public Snapshot snapshot(final long cacheSizeBytes, final int entries) {
        return new Snapshot(
                this.requests.sum(),
                this.hits.sum(),
                this.misses.sum(),
                this.invalidations.sum(),
                this.corruptions.sum(),
                this.readFailures.sum(),
                this.writeFailures.sum(),
                this.transformNanosSaved.sum(),
                this.cacheReadNanos.sum(),
                this.cacheWriteNanos.sum(),
                this.bytesRead.sum(),
                this.bytesWritten.sum(),
                this.frontendTransformNanos.sum(),
                this.verifyMatches.sum(),
                this.verifyMismatches.sum(),
                cacheSizeBytes,
                entries);
    }

    public record Snapshot(
            long requests,
            long hits,
            long misses,
            long invalidations,
            long corruptions,
            long readFailures,
            long writeFailures,
            long transformNanosSaved,
            long cacheReadNanos,
            long cacheWriteNanos,
            long bytesRead,
            long bytesWritten,
            long frontendTransformNanos,
            long verifyMatches,
            long verifyMismatches,
            long cacheSizeBytes,
            int entries) {
    }
}
