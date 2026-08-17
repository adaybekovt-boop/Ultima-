package dev.ultima.client.renderer.retained;

/** Opt-in counter for successful bounded command-buffer compactions. */
public final class RetainedCompactionDebug {
    public static final boolean ENABLED = Boolean.getBoolean("ultima.retained.debugCompaction");
    private static int successfulCompactions;

    private RetainedCompactionDebug() {
    }

    public static void reset() {
        successfulCompactions = 0;
    }

    public static void recordSuccessfulCompaction() {
        if (ENABLED) {
            successfulCompactions++;
        }
    }

    public static int successfulCompactions() {
        return successfulCompactions;
    }
}
