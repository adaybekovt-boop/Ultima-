package dev.ultima.inventory;

/**
 * Read-only view of slot occupancy. Production code wraps a vanilla {@code Container};
 * tests wrap a fake inventory so the mask can be proven without a live world.
 */
@FunctionalInterface
public interface SlotOccupancy {
    boolean isOccupied(int slot);

    default int size() {
        throw new UnsupportedOperationException("size()");
    }

    static SlotOccupancy of(final int size, final java.util.function.IntPredicate occupied) {
        return new SlotOccupancy() {
            @Override
            public boolean isOccupied(final int slot) {
                return occupied.test(slot);
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
