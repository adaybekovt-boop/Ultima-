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
 * <p>If vanilla mutates the backing list and then throws (typical: {@code items.set} then
 * {@code setChanged} / comparator update), {@code noteSlot} never runs. Nested
 * {@code onUnindexedMutation} ignores {@code setChanged} while depth {@code > 0}. The
 * {@code finally} block therefore untrusts the whole mask (option a): the next occupancy
 * walk does an honest rescan. False-negatives ("trusted empty" after a successful write)
 * are forbidden; losing the per-slot bit is acceptable.
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
        boolean noted = false;
        try {
            vanilla.run();
            noteSlotGuarded(container, slot);
            noted = true;
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
            if (!noted) {
                abortIndexedWrite(container);
            }
        }
    }

    public static <T> T callIndexedWrite(final Container container, final int slot, final Supplier<T> vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        boolean noted = false;
        try {
            T result = vanilla.get();
            noteSlotGuarded(container, slot);
            noted = true;
            return result;
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
            if (!noted) {
                abortIndexedWrite(container);
            }
        }
    }

    public static void runClear(final Container container, final Runnable vanilla) {
        SlotMaskTracker.enterSlotWrite(container);
        boolean noted = false;
        try {
            vanilla.run();
            noteClearedGuarded(container);
            noted = true;
        } finally {
            SlotMaskTracker.exitSlotWrite(container);
            if (!noted) {
                abortIndexedWrite(container);
            }
        }
    }

    /**
     * Runs {@code vanilla} and always invalidates afterwards, including when {@code vanilla}
     * throws. Used by {@code unpackLootTable}, {@code applyComponents}, bulk replace, and NBT load.
     */
    public static void runInvalidate(final Container container, final Runnable vanilla) {
        try {
            vanilla.run();
        } finally {
            SlotMaskTracker.invalidate(container);
        }
    }

    /**
     * Same as {@link #runInvalidate} when {@code shouldInvalidate} is true. When false, runs
     * vanilla only: {@code unpackLootTable} is a no-op after the table is cleared, and wrapping
     * that path with an unconditional invalidate would untrust every chest {@code getItem}.
     */
    public static void runInvalidateIf(
            final Container container, final boolean shouldInvalidate, final Runnable vanilla) {
        if (!shouldInvalidate) {
            vanilla.run();
            return;
        }
        runInvalidate(container, vanilla);
    }

    /**
     * Invalidates after vanilla when {@code self} is a {@link Container}. Used by
     * {@code BlockEntity} wraps that also run on non-container block entities.
     */
    public static void runInvalidateIfContainer(final Object self, final Runnable vanilla) {
        if (self instanceof Container container) {
            runInvalidate(container, vanilla);
            return;
        }
        vanilla.run();
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

    /**
     * Option (a): any exceptional exit from an indexed write may have mutated the backing
     * list before {@code noteSlot} ran ({@code items.set} then {@code setChanged} throw).
     * Nested {@code setChanged} is ignored while {@code slotWriteDepth > 0}, so the only
     * safe recovery is to untrust the whole mask. Direct {@link SlotMaskTracker#invalidate}
     * is intentional: a tripped FailOpenGuard must not skip this.
     */
    private static void abortIndexedWrite(final Container container) {
        SlotMaskTracker.invalidate(container);
    }
}
