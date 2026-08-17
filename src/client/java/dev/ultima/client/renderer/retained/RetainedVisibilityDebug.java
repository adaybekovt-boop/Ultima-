package dev.ultima.client.renderer.retained;

/**
 * Opt-in diagnostics for the A2 hidden-remesh visibility transition.
 *
 * <p>The flag is false by default and the counters are only touched from the
 * retained renderer when explicitly enabled by a JVM property.</p>
 */
public final class RetainedVisibilityDebug {
    public static final boolean ENABLED = Boolean.getBoolean("ultima.retained.debugVisibilityReentry");

    private static int sameFrameReentries;
    private static int oneFrameLateReentries;

    private RetainedVisibilityDebug() {
    }

    public static void reset() {
        sameFrameReentries = 0;
        oneFrameLateReentries = 0;
    }

    public static void recordReentry(final boolean drewSameFrame) {
        if (!ENABLED) {
            return;
        }
        if (drewSameFrame) {
            sameFrameReentries++;
        } else {
            oneFrameLateReentries++;
        }
    }

    public static int sameFrameReentries() {
        return sameFrameReentries;
    }

    public static int oneFrameLateReentries() {
        return oneFrameLateReentries;
    }
}
