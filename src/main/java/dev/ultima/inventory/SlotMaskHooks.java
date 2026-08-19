package dev.ultima.inventory;

import net.minecraft.world.Container;

/**
 * Called from Mixins. Kept allocation-free on the hot path besides the weak-map lookup.
 */
public final class SlotMaskHooks {
    private SlotMaskHooks() {
    }

    public static void beforeIndexedWrite(final Container container) {
        SlotMaskTracker.enterSlotWrite(container);
    }

    public static void afterIndexedWrite(final Container container, final int slot) {
        try {
            SlotMaskTracker.noteSlot(container, slot);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void afterClear(final Container container) {
        SlotMaskTracker.noteCleared(container);
    }

    public static void afterClearWrapped(final Container container) {
        try {
            SlotMaskTracker.noteCleared(container);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void afterSetChanged(final Container container) {
        SlotMaskTracker.noteUnindexedMutation(container);
    }

    public static void invalidate(final Container container) {
        SlotMaskTracker.invalidate(container);
    }
}
