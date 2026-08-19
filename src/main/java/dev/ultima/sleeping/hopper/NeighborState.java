package dev.ultima.sleeping.hopper;

public record NeighborState(NeighborKind kind, Occupancy occupancy, boolean unresolvedLoot) {
    public static NeighborState absent() {
        return new NeighborState(NeighborKind.ABSENT, Occupancy.NA, false);
    }

    public static NeighborState unknown() {
        return new NeighborState(NeighborKind.UNKNOWN, Occupancy.NA, false);
    }

    public static NeighborState sleepSafe(final Occupancy occupancy) {
        return new NeighborState(NeighborKind.SLEEP_SAFE, occupancy, false);
    }

    public boolean forbidsSleep() {
        return this.kind == NeighborKind.UNKNOWN || this.unresolvedLoot;
    }
}
