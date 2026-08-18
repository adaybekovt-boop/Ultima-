package dev.ultima.inventory;

/**
 * Implemented by Mixins on vanilla {@code Container} classes that participate in slot-mask
 * tracking. Unknown / modded containers do not implement this, so scans fall through to vanilla.
 */
public interface SlotMaskHolder {
    NonEmptySlotMask ultima$slotMask();
}
