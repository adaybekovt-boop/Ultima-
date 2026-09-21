package dev.ultima.client.broker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Low-overhead broker counters. Worker threads update thread-owned volatile fields; diagnostics
 * aggregate them later, avoiding atomic read-modify-write on every chunk task.
 */
public final class BrokerMetrics {
    private static final int LATENCY_SAMPLE_CAPACITY = 2_048;

    private final List<WorkerCounters> workerCounters = new ArrayList<>();
    private final ThreadLocal<WorkerCounters> localWorker = ThreadLocal.withInitial(this::registerWorker);
    private final long[] pendingAgeSamples = new long[LATENCY_SAMPLE_CAPACITY];
    private final long[] firstRenderableSamples = new long[LATENCY_SAMPLE_CAPACITY];
    private final long observationStartedNanos = System.nanoTime();

    private int pendingAgeSampleCursor;
    private int pendingAgeSampleCount;
    private int firstRenderableSampleCursor;
    private int firstRenderableSampleCount;

    volatile long frames;
    volatile long frameWallNanos;
    volatile long frameCpuNanos;
    volatile long gpuFrameNanos;
    volatile long admissionAttempts;
    volatile long admissions;
    volatile long deferrals;
    volatile long urgentBypasses;
    volatile long starvationBypasses;
    volatile long sodiumTaskSubmissions;
    volatile long meshReadyResults;
    volatile long pendingAgeNanosTotal;
    volatile long maximumPendingAgeNanos;
    volatile long uploadBatches;
    volatile long uploadCompletions;
    volatile long uploadNanos;
    volatile long bytesUploaded;
    volatile long firstRenderableCount;
    volatile long requestToFirstRenderableNanosTotal;
    volatile long maximumRequestToFirstRenderableNanos;
    volatile long initialBuildRequests;
    volatile long cancelledInitialBuilds;
    volatile int outstandingInitialBuilds;
    volatile int maximumOutstandingInitialBuilds;
    volatile int maximumQueueDepth;
    volatile int lastQueueDepth;
    volatile int lastBusyWorkers;
    volatile int lastTotalWorkers;
    volatile long resets;

    public void workerTaskStarted() {
        this.localWorker.get().starts++;
    }

    public void workerTaskCompleted() {
        this.localWorker.get().completions++;
    }

    /** Render-thread only; a bounded primitive ring keeps task admission allocation-free. */
    public void recordPendingAge(final long nanos) {
        this.pendingAgeSamples[this.pendingAgeSampleCursor] = nanos;
        this.pendingAgeSampleCursor = (this.pendingAgeSampleCursor + 1) % LATENCY_SAMPLE_CAPACITY;
        this.pendingAgeSampleCount = Math.min(LATENCY_SAMPLE_CAPACITY, this.pendingAgeSampleCount + 1);
    }

    /** Render-thread only; percentile sorting is deferred to the diagnostics snapshot path. */
    public void recordFirstRenderableAge(final long nanos) {
        this.firstRenderableSamples[this.firstRenderableSampleCursor] = nanos;
        this.firstRenderableSampleCursor = (this.firstRenderableSampleCursor + 1) % LATENCY_SAMPLE_CAPACITY;
        this.firstRenderableSampleCount = Math.min(LATENCY_SAMPLE_CAPACITY, this.firstRenderableSampleCount + 1);
    }

    public void initialBuildRequested() {
        this.initialBuildRequests++;
        int outstanding = ++this.outstandingInitialBuilds;
        this.maximumOutstandingInitialBuilds = Math.max(this.maximumOutstandingInitialBuilds, outstanding);
    }

    public void initialBuildFinished(final boolean cancelled) {
        if (this.outstandingInitialBuilds > 0) {
            this.outstandingInitialBuilds--;
        }
        if (cancelled) {
            this.cancelledInitialBuilds++;
        }
    }

    public Snapshot snapshot(final AdmissionControllerView controller) {
        long starts = 0L;
        long completions = 0L;
        int workers;
        synchronized (this.workerCounters) {
            workers = this.workerCounters.size();
            for (WorkerCounters counters : this.workerCounters) {
                starts += counters.starts;
                completions += counters.completions;
            }
        }
        return new Snapshot(
                this.frames,
                this.frameWallNanos,
                this.frameCpuNanos,
                this.gpuFrameNanos,
                this.admissionAttempts,
                this.admissions,
                this.deferrals,
                this.urgentBypasses,
                this.starvationBypasses,
                this.sodiumTaskSubmissions,
                this.meshReadyResults,
                starts,
                completions,
                Math.max(0L, System.nanoTime() - this.observationStartedNanos),
                this.pendingAgeNanosTotal,
                this.maximumPendingAgeNanos,
                this.pendingAgeSampleCount,
                percentile(this.pendingAgeSamples, this.pendingAgeSampleCount, 0.95),
                percentile(this.pendingAgeSamples, this.pendingAgeSampleCount, 0.99),
                this.uploadBatches,
                this.uploadCompletions,
                this.uploadNanos,
                this.bytesUploaded,
                this.firstRenderableCount,
                this.requestToFirstRenderableNanosTotal,
                this.maximumRequestToFirstRenderableNanos,
                this.firstRenderableSampleCount,
                percentile(this.firstRenderableSamples, this.firstRenderableSampleCount, 0.95),
                percentile(this.firstRenderableSamples, this.firstRenderableSampleCount, 0.99),
                this.initialBuildRequests,
                this.cancelledInitialBuilds,
                this.outstandingInitialBuilds,
                this.maximumOutstandingInitialBuilds,
                this.maximumQueueDepth,
                this.lastQueueDepth,
                this.lastBusyWorkers,
                this.lastTotalWorkers,
                workers,
                this.resets,
                controller);
    }

    private static long percentile(final long[] ring, final int count, final double quantile) {
        if (count <= 0) {
            return 0L;
        }
        long[] sorted = Arrays.copyOf(ring, count);
        Arrays.sort(sorted);
        int index = Math.max(0, (int)Math.ceil(quantile * count) - 1);
        return sorted[Math.min(index, count - 1)];
    }

    private WorkerCounters registerWorker() {
        WorkerCounters counters = new WorkerCounters();
        synchronized (this.workerCounters) {
            this.workerCounters.add(counters);
        }
        return counters;
    }

    private static final class WorkerCounters {
        volatile long starts;
        volatile long completions;
    }

    public record AdmissionControllerView(
            String mode,
            boolean pressureHigh,
            int frameSamples,
            long p95CpuFrameNanos,
            long p99CpuFrameNanos,
            long p999CpuFrameNanos,
            long maximumObservedDeferredNanos) {
    }

    public record Snapshot(
            long frames,
            long frameWallNanosTotal,
            long frameCpuNanosTotal,
            long gpuFrameNanosTotal,
            long admissionAttempts,
            long admissions,
            long deferrals,
            long urgentBypasses,
            long starvationBypasses,
            long sodiumTaskSubmissions,
            long meshReadyResults,
            long workerTaskStarts,
            long workerTaskCompletions,
            long observationDurationNanos,
            long pendingAgeNanosTotal,
            long maximumPendingAgeNanos,
            int pendingAgeSampleCount,
            long p95PendingAgeNanos,
            long p99PendingAgeNanos,
            long uploadBatches,
            long uploadCompletions,
            long uploadNanos,
            long bytesUploaded,
            long firstRenderableCount,
            long requestToFirstRenderableNanosTotal,
            long maximumRequestToFirstRenderableNanos,
            int firstRenderableSampleCount,
            long p95RequestToFirstRenderableNanos,
            long p99RequestToFirstRenderableNanos,
            long initialBuildRequests,
            long cancelledInitialBuilds,
            int outstandingInitialBuilds,
            int maximumOutstandingInitialBuilds,
            int maximumQueueDepth,
            int lastQueueDepth,
            int lastBusyWorkers,
            int lastTotalWorkers,
            int observedWorkerThreads,
            long resets,
            AdmissionControllerView controller) {
    }
}
