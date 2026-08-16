package dev.ultima.visibility.gpu;

import java.util.BitSet;

/**
 * Future GPU visibility wiring only. Not a mesh-shader renderer, not Hi-Z, and
 * not a vendor path. CPU retained visibility writes a compact mask; a later GPU
 * pass may consume the same mask to generate or compact indirect commands.
 *
 * <pre>
 * visibility source → compact command visibility mask → indirect command generation/compaction
 * </pre>
 */
public final class GpuVisibilityContract {
    private GpuVisibilityContract() {
    }

    @FunctionalInterface
    public interface VisibilitySource {
        void writeVisibleSlots(BitSet dest, int slotCount);
    }

    @FunctionalInterface
    public interface CommandVisibilityMask {
        void applyVisibleSlots(BitSet visibleSlots, int slotCount);
    }

    /**
     * Copies {@code source} into {@code mask} without approximating occupancy.
     * Callers must pass the vanilla-visible set, not a guessed occlusion set.
     */
    public static void transfer(final VisibilitySource source, final CommandVisibilityMask mask, final int slotCount) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount must be >= 0");
        }
        BitSet visible = new BitSet(slotCount);
        source.writeVisibleSlots(visible, slotCount);
        if (visible.length() > slotCount) {
            visible.clear(slotCount, visible.length());
        }
        mask.applyVisibleSlots(visible, slotCount);
    }
}
