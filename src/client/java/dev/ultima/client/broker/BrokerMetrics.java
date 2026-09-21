package dev.ultima.client.broker;

import java.util.ArrayList;
import java.util.List;

/**
 * Low-overhead broker counters. Worker threads update thread-owned volatile fields; diagnostics
 * aggregate them later, avoiding atomic read-modify-write on every chunk task.
 */
public final class BrokerMetrics {
    private final List<WorkerCounters> workerCounters = new ArrayList<>();
    private final ThreadLocal<WorkerCounters> localWorker = ThreadLocal.withInitial(this::registerWorker);

    volatile long frames;
    volatile long frameWallNanos;
    volatile long frameCpuNanos;
    volatile long gpuFrameNanos;
    volatile long admissionAttempts;
    volatile long admissions;
    volatile long deferrals;
    volatile long urgentBypasses;
    volatile long sodiumTaskSubmissions;
    volatile long pendingAgeNanosTotal;
    volatile long maximumPendingAgeNanos;
    volatile long uploadBatches;
    volatile long uploadCompletions;
    volatile long uploadNanos;
    volatile long bytesUploaded;
    volatile long firstRenderableCount;
    volatile long requestToFirstRenderableNanosTotal;
    volatile long maximumRequestToFirstRenderableNanos;
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
                this.sodiumTaskSubmissions,
                starts,
                completions,
                this.pendingAgeNanosTotal,
                this.maximumPendingAgeNanos,
                this.uploadBatches,
                this.uploadCompletions,
                this.uploadNanos,
                this.bytesUploaded,
                this.firstRenderableCount,
                this.requestToFirstRenderableNanosTotal,
                this.maximumRequestToFirstRenderableNanos,
                this.maximumQueueDepth,
                this.lastQueueDepth,
                this.lastBusyWorkers,
                this.lastTotalWorkers,
                workers,
                this.resets,
                controller);
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
            long sodiumTaskSubmissions,
            long workerTaskStarts,
            long workerTaskCompletions,
            long pendingAgeNanosTotal,
            long maximumPendingAgeNanos,
            long uploadBatches,
            long uploadCompletions,
            long uploadNanos,
            long bytesUploaded,
            long firstRenderableCount,
            long requestToFirstRenderableNanosTotal,
            long maximumRequestToFirstRenderableNanos,
            int maximumQueueDepth,
            int lastQueueDepth,
            int lastBusyWorkers,
            int lastTotalWorkers,
            int observedWorkerThreads,
            long resets,
            AdmissionControllerView controller) {
    }
}
