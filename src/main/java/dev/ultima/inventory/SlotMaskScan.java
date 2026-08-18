package dev.ultima.inventory;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Conservative uses of {@link NonEmptySlotMask}: skip empty slots while still reading each
 * candidate from the live container, and rebuild from a full scan on first use / periodically.
 */
public final class SlotMaskScan {
    private SlotMaskScan() {
    }

    public static void ensureAttached(final SlotMaskHolder holder, final Container container) {
        NonEmptySlotMask mask = holder.ultima$slotMask();
        if (mask.slotCount() != container.getContainerSize()) {
            mask.markUntrusted();
        }
    }

    public static boolean isEmpty(final Container container, final NonEmptySlotMask mask) {
        if (mask.slotCount() != container.getContainerSize()) {
            mask.markUntrusted();
            return vanillaIsEmpty(container);
        }
        mask.noteUse();
        if (mask.shouldVerify()) {
            return verifyAndIsEmpty(container, mask);
        }
        boolean[] found = {false};
        mask.forEachSetBit(slot -> {
            if (!found[0] && !container.getItem(slot).isEmpty()) {
                found[0] = true;
            }
        });
        return !found[0];
    }

    /**
     * @return the comparator signal, or {@code null} to keep the vanilla loop (size mismatch /
     *         untracked container)
     */
    public static @Nullable Integer redstoneSignal(final Container container, final NonEmptySlotMask mask) {
        int size = container.getContainerSize();
        if (size == 0) {
            return 0;
        }
        if (mask.slotCount() != size) {
            mask.markUntrusted();
            return null;
        }
        mask.noteUse();
        if (mask.shouldVerify()) {
            verifyAndIsEmpty(container, mask);
        }
        float totalPercent = 0.0F;
        float[] sum = {0.0F};
        mask.forEachSetBit(slot -> {
            ItemStack itemStack = container.getItem(slot);
            if (!itemStack.isEmpty()) {
                sum[0] += (float)itemStack.getCount() / container.getMaxStackSize(itemStack);
            }
        });
        totalPercent = sum[0] / size;
        return Mth.lerpDiscrete(totalPercent, 0, 15);
    }

    private static boolean verifyAndIsEmpty(final Container container, final NonEmptySlotMask mask) {
        int size = mask.slotCount();
        boolean[] occupied = new boolean[size];
        boolean empty = true;
        for (int slot = 0; slot < size; slot++) {
            occupied[slot] = !container.getItem(slot).isEmpty();
            if (occupied[slot]) {
                empty = false;
            }
        }
        mask.verifyAgainst(occupied);
        return empty;
    }

    private static boolean vanillaIsEmpty(final Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
