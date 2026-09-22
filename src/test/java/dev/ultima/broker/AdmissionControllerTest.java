package dev.ultima.broker;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Controller safety contracts: urgent bypass, starvation escape, stale fallback, and reset. */
public final class AdmissionControllerTest {
    private static final long MS = 1_000_000L;
    private AdmissionControllerTest() {
    }

    public static void main(final String[] args) {
        urgentAlwaysBypasses();
        staleTelemetryRestoresOwnerBehavior();
        overloadedQueueCannotStarve();
        queueNeverDrainsWithoutPermits();
        gateNeverDequeuesOrLosesWork();
        admittedWorkIsDequeuedExactlyOnce();
        resetDropsOldPressure();
        staticControlIsDeterministic();
        singleSpikeExitsAfterHealthyFrames();
        continuousOverloadStaysHigh();
        oscillationAroundThresholdDoesNotFlap();
        unknownGpuIsNotHealthy();
        zeroGpuIsARealSample();
        minimumIntervalDoesNotReleaseEarly();
    }

    private static AdmissionController controller() {
        return new AdmissionController(
                AdmissionController.Mode.CONTROL,
                new AdmissionController.Config(10 * MS, 100 * MS, 20 * MS, 50 * MS, 2));
    }

    private static void makePressureHigh(final AdmissionController controller, final long now) {
        for (int index = 0; index < 16; index++) {
            controller.onFrame(now + index * MS, 30 * MS, -1, -1);
        }
        controller.onPipelinePressure(100, 4, 4);
    }

    private static void urgentAlwaysBypasses() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        require(controller.permit(1_020 * MS, true), "urgent task was deferred");
    }

    private static void staleTelemetryRestoresOwnerBehavior() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        require(controller.permit(2_000 * MS, false), "stale telemetry did not fail open");
    }

    private static void overloadedQueueCannotStarve() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        require(controller.permit(1_016 * MS, false), "first admission must establish baseline");
        require(!controller.permit(1_017 * MS, false), "high pressure did not defer");
        require(controller.permit(1_067 * MS, false), "maximum defer escape did not admit");
    }

    private static void queueNeverDrainsWithoutPermits() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 5_000 * MS);
        int permits = 0;
        for (long now = 5_016 * MS; now < 5_300 * MS; now += 5 * MS) {
            controller.onFrame(now, 30 * MS, -1, -1);
            controller.onPipelinePressure(100, 4, 4);
            if (controller.permit(now, false)) {
                permits++;
            }
        }
        require(permits > 0, "queue received no admission permits");
    }

    private static void gateNeverDequeuesOrLosesWork() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        ArrayDeque<Integer> ownerQueue = new ArrayDeque<>();
        ownerQueue.add(1);
        ownerQueue.add(2);
        controller.onPipelinePressure(ownerQueue.size(), 4, 4);
        require(controller.permit(1_016 * MS, false), "baseline admission missing");
        require(ownerQueue.removeFirst() == 1, "owner dequeue order changed");
        controller.onPipelinePressure(ownerQueue.size(), 4, 4);
        int before = ownerQueue.size();
        require(!controller.permit(1_017 * MS, false), "high pressure did not close gate");
        require(ownerQueue.size() == before && ownerQueue.peekFirst() == 2,
                "a denied admission transferred ownership or dequeued work");
        require(controller.permit(1_018 * MS, true), "urgent owner path was denied");
        require(ownerQueue.removeFirst() == 2, "urgent owner dequeue changed");
    }

    private static void admittedWorkIsDequeuedExactlyOnce() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 5_000 * MS);
        ArrayDeque<Integer> ownerQueue = new ArrayDeque<>();
        for (int value = 0; value < 32; value++) {
            ownerQueue.add(value);
        }
        Set<Integer> completed = new HashSet<>();
        for (long now = 5_016 * MS; now < 7_000 * MS && !ownerQueue.isEmpty(); now += 5 * MS) {
            controller.onFrame(now, 30 * MS, -1L, -1L);
            controller.onPipelinePressure(ownerQueue.size(), 4, 4);
            if (controller.permit(now, false)) {
                Integer work = ownerQueue.removeFirst();
                require(completed.add(work), "owner work dequeued twice");
            }
        }
        require(ownerQueue.isEmpty(), "starvation escape did not eventually drain owner queue");
        require(completed.size() == 32, "owner work was lost");
    }

    private static void resetDropsOldPressure() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        controller.reset();
        controller.onPipelinePressure(10, 1, 4);
        require(controller.permit(1_100 * MS, false), "reset retained stale pressure");
        require(!controller.snapshot().pressureHigh(), "pressure latch survived reset");
    }

    private static void staticControlIsDeterministic() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.STATIC,
                new AdmissionController.Config(10 * MS, 100 * MS, 20 * MS, 50 * MS, 3));
        controller.onFrame(100 * MS, 5 * MS, -1, -1);
        controller.onPipelinePressure(10, 1, 4);
        require(controller.permit(101 * MS, false), "static frame zero must permit");
        require(!controller.permit(102 * MS, false), "static frame one must defer");
        require(!controller.permit(103 * MS, false), "static frame two must defer");
        require(controller.permit(104 * MS, false), "static frame three must permit");
    }

    private static void singleSpikeExitsAfterHealthyFrames() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.CONTROL, AdmissionController.Config.defaults());
        long target = AdmissionController.Config.defaults().targetFrameNanos();
        long now = 1_000_000_000L;
        controller.onFrame(now, target * 3L, -1L, -1L);
        controller.onPipelinePressure(4, 1, 4);
        require(controller.snapshot().pressureHigh(), "one severe frame must enter pressure");
        for (int frame = 1; frame <= 7; frame++) {
            controller.onFrame(now + frame * target, target, -1L, -1L);
        }
        require(controller.snapshot().pressureHigh(), "pressure must not clear before eight healthy frames");
        controller.onFrame(now + 8L * target, target, -1L, -1L);
        require(!controller.snapshot().pressureHigh(), "eight healthy frames must exit pressure");
    }

    private static void continuousOverloadStaysHigh() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.CONTROL, AdmissionController.Config.defaults());
        long target = AdmissionController.Config.defaults().targetFrameNanos();
        long now = 2_000_000_000L;
        for (int frame = 0; frame < 32; frame++) {
            controller.onFrame(now + frame * target, target * 2L + 1L, -1L, -1L);
        }
        require(controller.snapshot().pressureHigh(), "continuous overload must stay high");
    }

    private static void oscillationAroundThresholdDoesNotFlap() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.CONTROL, AdmissionController.Config.defaults());
        long target = AdmissionController.Config.defaults().targetFrameNanos();
        long now = 3_000_000_000L;
        boolean entered = false;
        int transitions = 0;
        boolean previous = false;
        for (int frame = 0; frame < 40; frame++) {
            long sample = frame % 2 == 0 ? target * 130L / 100L : target * 90L / 100L;
            controller.onFrame(now + frame * target, sample, -1L, -1L);
            boolean high = controller.snapshot().pressureHigh();
            entered |= high;
            if (high != previous) {
                transitions++;
                previous = high;
            }
        }
        require(!entered, "alternating frames around the threshold must not enter pressure");
        require(transitions == 0, "threshold oscillation flapped the pressure latch");
    }

    private static void unknownGpuIsNotHealthy() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.CONTROL, AdmissionController.Config.defaults());
        long target = AdmissionController.Config.defaults().targetFrameNanos();
        controller.onFrame(10_000L, target, -1L, -1L);
        require("NO_DATA".equals(controller.gpuSampleKind()), "negative GPU sample must be no data");
        require(!controller.snapshot().pressureHigh(), "unknown GPU must not look overloaded");
        controller.onFrame(20_000L, target, 0L, -1L);
        require("ZERO".equals(controller.gpuSampleKind()), "zero GPU sample must stay distinct from no data");
    }

    private static void zeroGpuIsARealSample() {
        AdmissionController controller = new AdmissionController(
                AdmissionController.Mode.CONTROL, AdmissionController.Config.defaults());
        long target = AdmissionController.Config.defaults().targetFrameNanos();
        controller.onFrame(10_000L, target * 3L, target * 3L, -1L);
        require(controller.snapshot().pressureHigh(), "a valid high GPU sample must enter pressure");
        require("VALID".equals(controller.gpuSampleKind()), "positive GPU sample must be valid");
        for (int frame = 0; frame < 8; frame++) {
            controller.onFrame(20_000L + frame, target, 0L, -1L);
        }
        require(!controller.snapshot().pressureHigh(), "a real zero GPU sample may satisfy the GPU exit check");
        require("ZERO".equals(controller.gpuSampleKind()), "exit must not rewrite a zero sample into no data");
    }

    private static void minimumIntervalDoesNotReleaseEarly() {
        AdmissionController controller = controller();
        makePressureHigh(controller, 1_000 * MS);
        require(controller.permit(1_016 * MS, false), "baseline admission");
        require(!controller.permit(1_016 * MS + 20 * MS, false),
                "minimum permit interval must not release before maximum defer");
        require(controller.permit(1_016 * MS + 50 * MS, false), "maximum defer must release");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
