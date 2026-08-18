package dev.ultima.sleeping;

import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hopper-level fail-open. If a sleep proof or wake check throws (modded
 * {@code Container}, unexpected {@code CompoundContainer}, inspector fault),
 * that hopper is pinned to vanilla {@code tryMoveItems} polling. Other hoppers
 * are unchanged. Mirrors {@link dev.ultima.meshing.MesherSectionFailOpen}.
 */
public final class HopperSleepFailOpen {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-be-sleep");

    private static volatile Object testFaultHopperId;

    private HopperSleepFailOpen() {
    }

    public static void armTestFault(final Object hopperId) {
        testFaultHopperId = hopperId;
    }

    public static void clearTestFault() {
        testFaultHopperId = null;
    }

    public static boolean testFaultArmed() {
        return testFaultHopperId != null;
    }

    public static void maybeThrowForTest(final Object hopperId) {
        Object fault = testFaultHopperId;
        if (fault != null && fault.equals(hopperId)) {
            testFaultHopperId = null;
            throw new IllegalStateException("ultima hopper sleep test fault for " + hopperId);
        }
    }

    public static boolean guardBoolean(
            final Object hopperId, final SleepController controller, final BooleanSupplier action) {
        try {
            maybeThrowForTest(hopperId);
            return action.getAsBoolean();
        } catch (Throwable error) {
            failOpen(hopperId, controller, error);
            return false;
        }
    }

    public static void runVoid(final Object hopperId, final SleepController controller, final Runnable action) {
        try {
            maybeThrowForTest(hopperId);
            action.run();
        } catch (Throwable error) {
            failOpen(hopperId, controller, error);
        }
    }

    public static void failOpen(
            final Object hopperId, final SleepController controller, final Throwable error) {
        LOGGER.warn(
                "Hopper sleep failed open to vanilla polling for {} ({})",
                hopperId,
                error == null ? "unknown" : error.toString(),
                error);
        if (controller != null && !controller.isPinned()) {
            controller.pin("fail_open: " + (error == null ? "unknown" : error.toString()));
        }
    }
}
