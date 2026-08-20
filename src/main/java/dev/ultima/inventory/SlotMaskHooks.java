package dev.ultima.inventory;

import java.util.function.Supplier;
import net.minecraft.world.Container;

/**
 * Called from Mixins. Kept allocation-free on the hot path besides the weak-map lookup.
 *
 * <p>Indexed writes go through {@link #runIndexedWrite} / {@link #callIndexedWrite} so the
 * enter/note/exit depth counter is restored even when the vanilla method throws. HEAD/RETURN
 * inject pairs cannot provide that: Mixin {@code RETURN} is skipped on exceptional exit.
 */
public final class SlotMaskHooks {
    private SlotMaskHooks() {
    }

    public static void runIndexedWrite(final Container container, final int slot, final Runnable vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            vanilla.run();
            SlotMaskTracker.noteSlot(container, slot);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static <T> T callIndexedWrite(final Container container, final int slot, final Supplier<T> vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            T result = vanilla.get();
            SlotMaskTracker.noteSlot(container, slot);
            return result;
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void runClear(final Container container, final Runnable vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            vanilla.run();
            SlotMaskTracker.noteCleared(container);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void afterBulkReplace(final Container container) {
        SlotMaskTracker.invalidate(container);
    }

    public static void afterSetChanged(final Container container) {
        SlotMaskTracker.noteUnindexedMutation(container);
    }

    public static void invalidate(final Container container) {
        SlotMaskTracker.invalidate(container);
    }
}
