package dev.ultima.fsr;

/**
 * GPU-free policy for FSR1 pipeline readiness. A missing device or shader
 * source is transient and must retry; a failed compile is permanent fail-open.
 */
public final class FsrPipelineGate {
    public enum Action {
        PROCEED,
        WAIT_FOR_PIPELINES,
        FAIL_OPEN
    }

    private FsrPipelineGate() {
    }

    /**
     * @param compiled {@code true} when EASU and RCAS pipelines are compiled
     * @param compileFailed {@code true} after a hard compile/link failure
     */
    public static Action decide(final boolean compiled, final boolean compileFailed) {
        if (compiled) {
            return Action.PROCEED;
        }
        if (compileFailed) {
            return Action.FAIL_OPEN;
        }
        return Action.WAIT_FOR_PIPELINES;
    }
}
