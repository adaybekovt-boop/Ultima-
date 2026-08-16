package dev.ultima.retained;

/**
 * Cancellation policy for the vanilla opaque {@code renderGroup} path.
 *
 * <p>Vanilla is cancelled only after retained submission completes without
 * exception. A failed submit — before any draw or after partial GPU work —
 * must leave vanilla runnable on that same frame.
 */
public final class RetainedOpaqueSubmitDecision {
    public enum FaultPoint {
        NONE,
        BEFORE_ANY_DRAW,
        AFTER_FIRST_GROUP
    }

    public record Outcome(
            boolean completedSuccessfully,
            boolean issuedAnyDraw,
            boolean shouldCancelVanillaOpaque,
            String failureReason) {
    }

    private RetainedOpaqueSubmitDecision() {
    }

    public static boolean shouldCancelVanillaOpaque(final boolean completedSuccessfully) {
        return completedSuccessfully;
    }

    /**
     * Pure control-flow stand-in for {@code submitOpaque}. Production still
     * performs real GPU work; tests force the same cancel decision.
     */
    public static Outcome simulate(final FaultPoint fault, final int liveGroupCount) {
        boolean issuedAnyDraw = false;
        try {
            if (fault == FaultPoint.BEFORE_ANY_DRAW) {
                throw new IllegalStateException("forced failure before any draw");
            }
            int flushed = 0;
            for (int i = 0; i < liveGroupCount; i++) {
                flushed++;
                issuedAnyDraw = true;
                if (fault == FaultPoint.AFTER_FIRST_GROUP && flushed >= 1) {
                    throw new IllegalStateException("forced failure after first group");
                }
            }
            return new Outcome(true, issuedAnyDraw, true, null);
        } catch (RuntimeException e) {
            return new Outcome(false, issuedAnyDraw, false, e.getMessage());
        }
    }
}
