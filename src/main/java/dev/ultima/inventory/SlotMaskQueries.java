package dev.ultima.inventory;

import dev.ultima.failopen.FailOpenGuard;
import java.util.Arrays;
import java.util.function.IntConsumer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

/**
 * Consumption helpers for the slot-mask hint. Every method fails open to a vanilla scan when the
 * container is not allowlisted, has unresolved loot, or the hint is untrusted.
 */
public final class SlotMaskQueries {
    private SlotMaskQueries() {
    }

    public static @org.jspecify.annotations.Nullable Boolean tryExactEmpty(final Container container) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> tryExactEmptyUnchecked(container));
    }

    /**
     * @return {@code true} when occupied slots were visited via the mask in vanilla index order
     */
    public static boolean forEachOccupied(final Container container, final IntConsumer visitor) {
        try {
            FailOpenGuard.maybeThrowForTest(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container);
            return forEachOccupiedUnchecked(container, visitor);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, error);
            if (container != null) {
                SlotMaskTracker.invalidate(container);
                vanillaVisit(container, visitor);
            }
            return false;
        }
    }

    /**
     * Filters {@code slots} to currently occupied entries, preserving relative order. Used by hopper
     * extract: empty slots are no-ops in vanilla, so skipping them is equivalent when the hint is
     * trusted. Untrusted or disallowed containers receive the original array.
     */
    public static int[] filterOccupiedPreservingOrder(final Container container, final int[] slots) {
        try {
            FailOpenGuard.maybeThrowForTest(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container);
            return filterOccupiedPreservingOrderUnchecked(container, slots);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, error);
            if (container != null) {
                SlotMaskTracker.invalidate(container);
            }
            return slots;
        }
    }

    public static boolean shouldIterateOccupied(final Container container) {
        return FailOpenGuard.test(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK,
                container,
                () -> shouldIterateOccupiedUnchecked(container),
                false);
    }

    private static @org.jspecify.annotations.Nullable Boolean tryExactEmptyUnchecked(final Container container) {
        if (!VanillaSlotMaskAllowlist.mayConsume(container)) {
            return null;
        }
        if (container instanceof CompoundContainer compound) {
            Boolean left = tryExactEmptyUnchecked(CompoundContainerViews.left(compound));
            Boolean right = tryExactEmptyUnchecked(CompoundContainerViews.right(compound));
            if (left == null || right == null) {
                return null;
            }
            return left && right;
        }
        return SlotMaskTracker.of(container).tryExactEmpty(SlotMaskTracker.occupancy(container));
    }

    private static boolean forEachOccupiedUnchecked(final Container container, final IntConsumer visitor) {
        if (!VanillaSlotMaskAllowlist.mayConsume(container)) {
            vanillaVisit(container, visitor);
            return false;
        }
        if (container instanceof CompoundContainer compound) {
            return forEachOccupiedCompound(compound, visitor);
        }
        return SlotMaskTracker.of(container).forEachOccupied(SlotMaskTracker.occupancy(container), visitor);
    }

    private static int[] filterOccupiedPreservingOrderUnchecked(final Container container, final int[] slots) {
        if (slots.length == 0 || !VanillaSlotMaskAllowlist.mayConsume(container)) {
            return slots;
        }
        if (container instanceof CompoundContainer compound) {
            return filterCompound(compound, slots);
        }
        NonEmptySlotMask mask = SlotMaskTracker.of(container);
        if (!mask.prepareTrustedHint(SlotMaskTracker.occupancy(container))) {
            return slots;
        }
        return filterByHint(mask, slots);
    }

    private static boolean shouldIterateOccupiedUnchecked(final Container container) {
        if (!VanillaSlotMaskAllowlist.mayConsume(container) || container instanceof CompoundContainer) {
            return false;
        }
        NonEmptySlotMask mask = SlotMaskTracker.of(container);
        return mask.prepareTrustedHint(SlotMaskTracker.occupancy(container));
    }

    private static int[] filterCompound(final CompoundContainer compound, final int[] slots) {
        Container left = CompoundContainerViews.left(compound);
        Container right = CompoundContainerViews.right(compound);
        if (left == null || right == null) {
            return slots;
        }
        if (!shouldIterateOccupied(left) || !shouldIterateOccupied(right)) {
            return slots;
        }
        int leftSize = left.getContainerSize();
        NonEmptySlotMask leftMask = SlotMaskTracker.of(left);
        NonEmptySlotMask rightMask = SlotMaskTracker.of(right);
        int[] filtered = new int[slots.length];
        int n = 0;
        for (int slot : slots) {
            boolean occupied = slot < leftSize
                    ? leftMask.hintedOccupied(slot)
                    : rightMask.hintedOccupied(slot - leftSize);
            if (occupied) {
                filtered[n++] = slot;
            }
        }
        return n == slots.length ? slots : Arrays.copyOf(filtered, n);
    }

    private static int[] filterByHint(final NonEmptySlotMask mask, final int[] slots) {
        int[] filtered = new int[slots.length];
        int n = 0;
        for (int slot : slots) {
            if (mask.hintedOccupied(slot)) {
                filtered[n++] = slot;
            }
        }
        return n == slots.length ? slots : Arrays.copyOf(filtered, n);
    }

    private static boolean forEachOccupiedCompound(final CompoundContainer compound, final IntConsumer visitor) {
        Container left = CompoundContainerViews.left(compound);
        Container right = CompoundContainerViews.right(compound);
        if (left == null || right == null) {
            vanillaVisit(compound, visitor);
            return false;
        }
        int leftSize = left.getContainerSize();
        boolean leftHint = forEachOccupied(left, visitor);
        boolean rightHint = forEachOccupied(right, slot -> visitor.accept(slot + leftSize));
        return leftHint && rightHint;
    }

    private static void vanillaVisit(final Container container, final IntConsumer visitor) {
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            if (!container.getItem(slot).isEmpty()) {
                visitor.accept(slot);
            }
        }
    }
}
