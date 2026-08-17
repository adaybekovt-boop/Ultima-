package dev.ultima.sleeping.hopper;

/**
 * How a hopper neighbour inventory was classified for sleep eligibility.
 *
 * <p>{@link #UNKNOWN} always forbids sleep for an unlocked hopper. That includes furnaces,
 * brewing stands, crafters, composters, loot-unpacked chests, entity containers, and any
 * class that is not an exact vanilla allowlisted container.
 */
public enum NeighborKind {
    ABSENT,
    SLEEP_SAFE,
    UNKNOWN
}
