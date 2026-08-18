package dev.ultima.sleeping;

/**
 * Contract for a block-entity type that may skip its expensive tick when a proof table
 * says every remaining vanilla mutation has a synchronous wake channel.
 */
public interface SleepableTickable {
    SleepDecision evaluateSleep();

    void onWake(WakeChannel channel);
}
