package dev.ultima.sleeping;

/**
 * A synchronous source of events that can legally wake a sleeping block entity.
 *
 * <p>A channel is only considered covering a vanilla mutation if Ultima observes that
 * mutation on the same game tick, before the next {@code tryMoveItems}-equivalent
 * would have run (entities tick before block entities on the server).
 */
public enum WakeChannel {
    /** Cooldown/bookkeeping still runs on the vanilla ticker; expiry is observed there. */
    TICK_BOOKKEEPING,
    /** The hopper's own {@code setItem}/{@code removeItem}/{@code clearContent}. */
    SELF_INVENTORY,
    /** Allowlisted neighbour container mutations, including {@code setChanged} after merge-grows. */
    NEIGHBOR_INVENTORY,
    /** {@code Level#setBlock} at the hopper, the source cell, or the destination cell. */
    BLOCK_UPDATE,
    /** Item entities moving into or spawning in the suck AABB. */
    ITEM_ENTITY,
    /** {@code Container} entities (minecarts, chest boats) entering a source/dest cell. */
    CONTAINER_ENTITY
}
