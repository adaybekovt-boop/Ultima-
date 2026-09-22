package dev.ultima.benchmark;

/**
 * Success gates for killer-module A/B samples. A pretty frame graph with no measured work is not a pass.
 */
public final class KillerBenchmarkGates {
    public enum Result {
        PASS,
        FAIL,
        INVALID,
        NOT_APPLICABLE
    }

    private KillerBenchmarkGates() {
    }

    public static Result artifact(final long hits, final long reloads) {
        if (hits <= 0L || reloads <= 0L) {
            return Result.INVALID;
        }
        return Result.PASS;
    }

    public static Result broker(final boolean activeControlAvailable) {
        return activeControlAvailable ? Result.PASS : Result.NOT_APPLICABLE;
    }

    public static Result warmup(final long warmedOperations) {
        return warmedOperations > 0L ? Result.PASS : Result.NOT_APPLICABLE;
    }
}
