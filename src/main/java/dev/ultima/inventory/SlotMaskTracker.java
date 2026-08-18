package dev.ultima.inventory;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Per-container mask storage. Keys are weakly held so discarded inventories do not leak.
 *
 * <p>The map is identity-based ({@link WeakHashMap}); vanilla containers do not use value equality.
 */
public final class SlotMaskTracker {
    private static final Map<Container, NonEmptySlotMask> MASKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SlotMaskTracker() {
    }

    public static NonEmptySlotMask of(final Container container) {
        return MASKS.computeIfAbsent(container, ignored -> new NonEmptySlotMask());
    }

    public static SlotOccupancy occupancy(final Container container) {
        return SlotOccupancy.of(container.getContainerSize(), slot -> !container.getItem(slot).isEmpty());
    }

    public static void enterSlotWrite(final Container container) {
        of(container).enterSlotWrite();
    }

    public static void exitSlotWrite(final Container container) {
        of(container).exitSlotWrite();
    }

    public static void noteSlot(final Container container, final int slot) {
        ItemStack stack = slot >= 0 && slot < container.getContainerSize() ? container.getItem(slot) : ItemStack.EMPTY;
        of(container).onSlotOccupancy(slot, !stack.isEmpty());
    }

    public static void noteCleared(final Container container) {
        NonEmptySlotMask mask = of(container);
        if (mask.size() != container.getContainerSize()) {
            mask.rebuild(occupancy(container));
            return;
        }
        mask.onCleared();
    }

    public static void noteUnindexedMutation(final Container container) {
        of(container).onUnindexedMutation();
    }

    public static void invalidate(final Container container) {
        of(container).invalidate();
    }
}
