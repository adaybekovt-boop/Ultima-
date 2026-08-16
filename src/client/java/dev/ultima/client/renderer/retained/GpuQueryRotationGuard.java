package dev.ultima.client.renderer.retained;

/**
 * Pure state guard for asynchronous timestamp query rotations.
 *
 * <p>OpenGL query names are not readable until both timestamps in a pair have
 * been written. Keeping this predicate separate makes the first-frame and
 * re-enable behavior directly testable without creating a graphics device.</p>
 */
public final class GpuQueryRotationGuard {
    private GpuQueryRotationGuard() {
    }

    public static boolean canRead(final boolean[] completedRotations, final int rotation) {
        return rotation >= 0
                && rotation < completedRotations.length
                && completedRotations[rotation];
    }
}
