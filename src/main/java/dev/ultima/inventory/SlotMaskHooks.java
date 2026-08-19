package dev.ultima.inventory;

import dev.ultima.failopen.FailOpenGuard;
import net.minecraft.world.Container;

/**
 * Called from Mixins. Kept allocation-free on the hot path besides the weak-map lookup.
 */
public final class SlotMaskHooks {
    private SlotMaskHooks() {
    }

    public static void beforeIndexedWrite(final Container container) {
        FailOpenGuard.run(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> SlotMaskTracker.enterSlotWrite(container));
    }

    public static void afterIndexedWrite(final Container container, final int slot) {
        try {
            FailOpenGuard.maybeThrowForTest(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container);
            SlotMaskTracker.noteSlot(container, slot);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, error);
            SlotMaskTracker.invalidate(container);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void afterClear(final Container container) {
        FailOpenGuard.run(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> {
            SlotMaskTracker.noteCleared(container);
        });
    }

    public static void afterClearWrapped(final Container container) {
        try {
            FailOpenGuard.maybeThrowForTest(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container);
            SlotMaskTracker.noteCleared(container);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, error);
            SlotMaskTracker.invalidate(container);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void afterSetChanged(final Container container) {
        FailOpenGuard.run(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK,
                container,
                () -> SlotMaskTracker.noteUnindexedMutation(container));
    }

    public static void invalidate(final Container container) {
        FailOpenGuard.run(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> SlotMaskTracker.invalidate(container));
    }
}
