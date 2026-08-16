package dev.ultima.visibility;

import java.util.BitSet;

/**
 * Persistent CPU visibility bitset keyed by {@code ViewArea} slot (the same
 * index retained terrain uses as {@code firstInstance}). Vanilla frustum output
 * remains the correctness oracle; this structure only stores and diffs it.
 */
public final class VisibilityMask {
    private final BitSet bits = new BitSet();
    private int slotCount;

    public void ensureSlots(final int slotCount) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount must be >= 0");
        }
        if (slotCount < this.slotCount) {
            this.bits.clear(slotCount, this.slotCount);
        }
        this.slotCount = slotCount;
    }

    public int slotCount() {
        return this.slotCount;
    }

    public boolean isVisible(final int slot) {
        return slot >= 0 && slot < this.slotCount && this.bits.get(slot);
    }

    public void setVisible(final int slot, final boolean visible) {
        if (slot < 0 || slot >= this.slotCount) {
            throw new IllegalArgumentException("slot " + slot + " outside [0, " + this.slotCount + ")");
        }
        this.bits.set(slot, visible);
    }

    public void clear() {
        this.bits.clear();
    }

    public int visibleCount() {
        return this.bits.cardinality();
    }

    public void copyInto(final BitSet dest) {
        dest.clear();
        dest.or(this.bits);
    }

    public void replaceWith(final BitSet visibleSlots, final int slotCount) {
        this.ensureSlots(slotCount);
        this.bits.clear();
        if (visibleSlots.isEmpty()) {
            return;
        }
        int from = visibleSlots.nextSetBit(0);
        while (from >= 0 && from < slotCount) {
            this.bits.set(from);
            from = visibleSlots.nextSetBit(from + 1);
        }
    }

    public BitSet snapshot() {
        return (BitSet)this.bits.clone();
    }
}
