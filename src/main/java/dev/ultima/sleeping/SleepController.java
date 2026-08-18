package dev.ultima.sleeping;

import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Per-instance sleep/wake state machine used by hopper (and later other block entities).
 */
public final class SleepController {
    private SleepState state = SleepState.ACTIVE;
    private final SleepCircuitBreaker breaker = new SleepCircuitBreaker();
    private boolean workSinceSleep;
    private @Nullable WakeRegistry registry;
    private String lastRefuseReason = "";
    private boolean refusedCounted;

    public SleepState state() {
        return this.state;
    }

    public boolean isSleeping() {
        return this.state == SleepState.SLEEPING;
    }

    public boolean isPinned() {
        return this.state == SleepState.PINNED_POLLING;
    }

    public SleepCircuitBreaker breaker() {
        return this.breaker;
    }

    public String lastRefuseReason() {
        return this.lastRefuseReason;
    }

    public void recordWork() {
        this.workSinceSleep = true;
        this.breaker.onWork();
    }

    /**
     * Enter sleep only after a no-op expensive tick and a successful proof-table check.
     */
    public void tryEnterSleep(
            final SleepDecision decision,
            final WakeRegistry registry,
            final Level level,
            final long[] watchPositions,
            final long gameTime
    ) {
        if (this.state == SleepState.PINNED_POLLING) {
            return;
        }
        if (!decision.allowed()) {
            this.markRefused(decision.reason());
            return;
        }
        this.refusedCounted = false;
        if (this.state == SleepState.SLEEPING) {
            return;
        }
        this.registry = registry;
        this.workSinceSleep = false;
        this.breaker.onSleep(gameTime);
        this.state = SleepState.SLEEPING;
        registry.register(level, this, watchPositions);
        BlockEntitySleepMetrics.onSleepEnter();
    }

    /**
     * Test helper that uses an already-registered controller without a Minecraft {@link Level}.
     */
    public void enterSleepForTest(final SleepDecision decision, final long gameTime) {
        if (this.state == SleepState.PINNED_POLLING || this.state == SleepState.SLEEPING) {
            return;
        }
        if (!decision.allowed()) {
            this.markRefused(decision.reason());
            return;
        }
        this.refusedCounted = false;
        this.workSinceSleep = false;
        this.breaker.onSleep(gameTime);
        this.state = SleepState.SLEEPING;
        BlockEntitySleepMetrics.onSleepEnter();
    }

    private void markRefused(final String reason) {
        this.lastRefuseReason = reason;
        if (!this.refusedCounted) {
            this.refusedCounted = true;
            BlockEntitySleepMetrics.onRefused();
        }
    }

    public void wake(final WakeChannel channel, final long gameTime) {
        if (this.state != SleepState.SLEEPING) {
            return;
        }
        WakeRegistry registered = this.registry;
        if (registered != null) {
            registered.unregister(this);
        }
        this.registry = null;
        this.state = SleepState.ACTIVE;
        BlockEntitySleepMetrics.onWake();
        this.breaker.onWake(gameTime, this.workSinceSleep);
        if (this.breaker.isTripped()) {
            this.pin(this.breaker.tripDetail());
        }
    }

    public void pin(final String detail) {
        if (this.state == SleepState.SLEEPING) {
            WakeRegistry registered = this.registry;
            if (registered != null) {
                registered.unregister(this);
            }
            this.registry = null;
            BlockEntitySleepMetrics.onWake();
        }
        this.state = SleepState.PINNED_POLLING;
        BlockEntitySleepMetrics.onThrashTrip();
        this.lastRefuseReason = "circuit_breaker: " + detail;
    }

    public void detach() {
        if (this.state == SleepState.SLEEPING) {
            WakeRegistry registered = this.registry;
            if (registered != null) {
                registered.unregister(this);
            }
            this.registry = null;
            this.state = SleepState.ACTIVE;
            BlockEntitySleepMetrics.onWake();
        }
    }
}
