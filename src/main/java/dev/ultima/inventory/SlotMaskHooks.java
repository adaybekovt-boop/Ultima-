package dev.ultima.inventory;

import dev.ultima.failopen.FailOpenGuard;
import java.util.function.Supplier;
import net.minecraft.world.Container;

/**
 * Called from Mixins. Kept allocation-free on the hot path besides the weak-map lookup.
 *
 * <p>Indexed writes go through {@link #runIndexedWrite} / {@link #callIndexedWrite} so the
 * enter/note/exit depth counter is restored even when the vanilla method throws. HEAD/RETURN
 * inject pairs cannot provide that: Mixin {@code RETURN} is skipped on exceptional exit.
 *
 * <p>Bulk invalidation ({@link #runInvalidate}) and unindexed {@code setChanged}
 * ({@link #runUnindexedMutation}) use the same wrap + {@code try}/{@code finally} shape so
 * {@code unpackLootTable} and {@code BlockEntity.setChanged} cannot leave a trusted empty
 * mask after a mid-method throw.
 */
public final class SlotMaskHooks {
    private SlotMaskHooks() {
    }

    public static void runIndexedWrite(final Container container, final int slot, final Runnable vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            vanilla.run();
            noteSlotGuarded(container, slot);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static <T> T callIndexedWrite(final Container container, final int slot, final Supplier<T> vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            T result = vanilla.get();
            noteSlotGuarded(container, slot);
            return result;
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    public static void runClear(final Container container, final Runnable vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        try {
            vanilla.run();
            noteClearedGuarded(container);
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
        }
    }

    /**
     * Runs {@code vanilla} and always invalidates afterwards, including when {@code vanilla}
     * throws. Used by {@code unpackLootTable}, bulk replace, and NBT load wraps.
     */
    public static void runInvalidate(final Container container, final Runnable vanilla) {
        try {
            vanilla.run();
        } finally {
            SlotMaskTracker.invalidate(container);
        }
    }

    public static <T> T callInvalidate(final Container container, final Supplier<T> vanilla) {
        try {
            return vanilla.get();
        } finally {
            SlotMaskTracker.invalidate(container);
        }
    }

    /**
     * Runs {@code vanilla} and always publishes an unindexed mutation afterwards, including
     * when {@code vanilla} throws. Used by {@code setChanged} wraps.
     */
    public static void runUnindexedMutation(final Container container, final Runnable vanilla) {
        try {
            vanilla.run();
        } finally {
            afterSetChanged(container);
        }
    }

    public static void afterBulkReplace(final Container container) {
        SlotMaskTracker.invalidate(container);
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

    private static void noteSlotGuarded(final Container container, final int slot) {
        FailOpenGuard.run(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> {
            try {
                SlotMaskTracker.noteSlot(container, slot);
            } catch (Throwable error) {
                SlotMaskTracker.invalidate(container);
                throw error;
            }
        });
    }

    private static void noteClearedGuarded(final Container container) {
        FailOpenGuard.run(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> {
            try {
                SlotMaskTracker.noteCleared(container);
            } catch (Throwable error) {
                SlotMaskTracker.invalidate(container);
                throw error;
            }
        });
    }
}
