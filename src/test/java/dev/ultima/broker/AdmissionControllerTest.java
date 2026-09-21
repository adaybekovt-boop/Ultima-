package dev.ultima.broker;

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
        resetDropsOldPressure();
        staticControlIsDeterministic();
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
