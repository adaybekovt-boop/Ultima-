package dev.ultima.sleeping;

public enum SleepState {
    /** Vanilla tick path, including {@code tryMoveItems}. */
    ACTIVE,
    /** Proven idle: expensive hopper work is skipped; cooldown and {@code tickedGameTime} still run. */
    SLEEPING,
    /**
     * Sleep/wake thrashed without work. This instance stays on vanilla polling and is logged;
     * it is not a silent fallback.
     */
    PINNED_POLLING
}
