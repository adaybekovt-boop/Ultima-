package dev.ultima.sleeping.hopper;

/**
 * Observable hopper snapshot used by {@link HopperSleepPolicy}. Mixins fill this from the
 * world; tests construct it directly so the proof table can be exercised without a live Level.
 */
public record HopperSleepSnapshot(
        boolean exactVanillaHopperClass,
        boolean enabled,
        int cooldownTime,
        boolean ownEmpty,
        boolean ownFull,
        boolean unresolvedOwnLoot,
        NeighborState source,
        NeighborState dest,
        boolean pickupBlockedByFullCube,
        boolean itemEntitiesInSuckAabb
) {
    public static HopperSleepSnapshot lockedVanillaHopper() {
        return new HopperSleepSnapshot(
                true,
                false,
                0,
                true,
                false,
                false,
                NeighborState.absent(),
                NeighborState.absent(),
                false,
                false);
    }
}
