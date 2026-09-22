package dev.ultima.client.broker;

/** Bounded latency-ring and initial-build backlog accounting contracts. */
public final class BrokerMetricsTest {
    private BrokerMetricsTest() {
    }

    public static void main(final String[] args) {
        latencyPercentilesAreComputedOffHotPath();
        latencyRingsRemainBounded();
        initialBuildBacklogBalancesCompletionAndCancellation();
        workerCountersAggregateWithoutGlobalAtomicUpdates();
    }

    private static void latencyPercentilesAreComputedOffHotPath() {
        BrokerMetrics metrics = new BrokerMetrics();
        for (int value = 1; value <= 100; value++) {
            metrics.recordPendingAge(value);
            metrics.recordFirstRenderableAge(value * 10L);
        }
        BrokerMetrics.Snapshot snapshot = metrics.snapshot(controllerView());
        require(snapshot.pendingAgeSampleCount() == 100, "pending-age sample count changed");
        require(snapshot.p95PendingAgeNanos() == 95L, "pending-age p95 is wrong");
        require(snapshot.p99PendingAgeNanos() == 99L, "pending-age p99 is wrong");
        require(snapshot.firstRenderableSampleCount() == 100, "first-renderable sample count changed");
        require(snapshot.p95RequestToFirstRenderableNanos() == 950L, "first-renderable p95 is wrong");
        require(snapshot.p99RequestToFirstRenderableNanos() == 990L, "first-renderable p99 is wrong");
    }

    private static void latencyRingsRemainBounded() {
        BrokerMetrics metrics = new BrokerMetrics();
        for (int value = 1; value <= 10_000; value++) {
            metrics.recordPendingAge(value);
            metrics.recordFirstRenderableAge(value);
        }
        BrokerMetrics.Snapshot snapshot = metrics.snapshot(controllerView());
        require(snapshot.pendingAgeSampleCount() == 2_048, "pending-age ring grew beyond its bound");
        require(snapshot.firstRenderableSampleCount() == 2_048, "first-renderable ring grew beyond its bound");
        require(snapshot.p99PendingAgeNanos() >= 9_900L, "rolling window did not retain recent samples");
    }

    private static void initialBuildBacklogBalancesCompletionAndCancellation() {
        BrokerMetrics metrics = new BrokerMetrics();
        metrics.initialBuildRequested();
        metrics.initialBuildRequested();
        metrics.initialBuildFinished(false);
        BrokerMetrics.Snapshot half = metrics.snapshot(controllerView());
        require(half.initialBuildRequests() == 2L, "initial requests were not counted");
        require(half.outstandingInitialBuilds() == 1, "completed initial build remained outstanding");
        require(half.maximumOutstandingInitialBuilds() == 2, "maximum backlog was not retained");
        metrics.initialBuildFinished(true);
        BrokerMetrics.Snapshot done = metrics.snapshot(controllerView());
        require(done.outstandingInitialBuilds() == 0, "cancelled initial build remained outstanding");
        require(done.cancelledInitialBuilds() == 1L, "initial-build cancellation was not counted");
    }

    private static void workerCountersAggregateWithoutGlobalAtomicUpdates() {
        BrokerMetrics metrics = new BrokerMetrics();
        metrics.workerTaskStarted();
        metrics.workerTaskCompleted();
        BrokerMetrics.Snapshot snapshot = metrics.snapshot(controllerView());
        require(snapshot.workerTaskStarts() == 1L, "worker start was not aggregated");
        require(snapshot.workerTaskCompletions() == 1L, "worker completion was not aggregated");
        require(snapshot.observedWorkerThreads() == 1, "worker-local counter was not registered");
    }

    private static BrokerMetrics.AdmissionControllerView controllerView() {
        return new BrokerMetrics.AdmissionControllerView("trace", false, 0, 0L, 0L, 0L, 0L);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
