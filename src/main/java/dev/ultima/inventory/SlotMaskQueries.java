package dev.ultima.inventory;

import dev.ultima.failopen.FailOpenGuard;
import java.util.Arrays;
import java.util.function.IntConsumer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

/**
 * Consumption helpers for the slot-mask hint.
 *
 * <p>Every occupancy walk goes through {@link FailOpenGuard} wrappers
 * ({@code supply}/{@code test}/{@code callNullable}) with the {@link Container}
 * as the case id. Custom try/catch that only calls {@link FailOpenGuard#failOpen}
 * never trips {@link FailOpenGuard#isTripped} and never resets consecutive
 * failures. Mixins that call these methods must not wrap them again: an inner
 * fail-open that swallows the throw looks like success to an outer
 * {@code supply} and would {@code recordSuccess}.
 */
public final class SlotMaskQueries {
    private SlotMaskQueries() {
    }

    public static @org.jspecify.annotations.Nullable Boolean tryExactEmpty(final Container container) {
        return FailOpenGuard.callNullable(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK, container, () -> tryExactEmptyUnchecked(container));
    }

    /**
     * Walk occupied slots. {@code true} means the visitor already ran via the mask.
     *
     * <p>On fault, returns {@code false} <em>without</em> a vanilla rescan: a
     * mutating visitor may already have been applied to earlier slots.
     */
    public static boolean forEachOccupied(final Container container, final IntConsumer visitor) {
        return FailOpenGuard.test(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK,
                container,
                () -> forEachOccupiedUnchecked(container, visitor),
                false);
    }

    /**
     * Production hopper extract filter ({@code HopperInsertExtractMixin}).
     *
     * <p>Filters {@code slots} to currently occupied entries, preserving relative order.
     * Empty slots are no-ops in vanilla, so skipping them is equivalent when the hint is
     * trusted. Untrusted, tripped, or disallowed containers receive the original array.
     */
    public static int[] filterOccupiedPreservingOrder(final Container container, final int[] slots) {
        if (slots == null) {
            return slots;
        }
        return FailOpenGuard.supply(
                FailOpenGuard.Module.CONTAINER_SLOT_MASK,
                container,
                () -> filterOccupiedPreservingOrderUnchecked(container, slots),
                () -> slots);
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
        if (!shouldIterateOccupiedUnchecked(left) || !shouldIterateOccupiedUnchecked(right)) {
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
        boolean leftHint = forEachOccupiedUnchecked(left, visitor);
        boolean rightHint = forEachOccupiedUnchecked(right, slot -> visitor.accept(slot + leftSize));
        return leftHint && rightHint;
    }

    static void vanillaVisit(final Container container, final IntConsumer visitor) {
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            if (!container.getItem(slot).isEmpty()) {
                visitor.accept(slot);
            }
        }
    }
}
