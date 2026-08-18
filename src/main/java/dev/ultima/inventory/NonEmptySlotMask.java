package dev.ultima.inventory;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Conservative non-empty slot occupancy mask.
 *
 * <p>Bits are a <em>hint</em>, not a source of truth, until differential tests plus periodic
 * verification have shown the mutation hooks are complete. A set bit may over-approximate (the
 * slot is actually empty — extra visits, vanilla-equivalent). A missing bit is treated as a
 * tracking failure: verification rebuilds from a full scan and refuses to skip that slot.
 *
 * <p>Iteration walks the lowest set bit to the highest, matching {@code for (int i = 0; i < size;
 * i++)} order, never an arbitrary bit order.
 */
public final class NonEmptySlotMask {
    public static final int DEFAULT_VERIFY_PERIOD = 256;

    private final int slotCount;
    private final long[] words;
    private final int verifyPeriod;
    private boolean initialized;
    private boolean untrusted;
    private int uses;
    private int verifyCount;
    private int missingNonEmptyDetections;

    public NonEmptySlotMask(final int slotCount) {
        this(slotCount, DEFAULT_VERIFY_PERIOD);
    }

    public NonEmptySlotMask(final int slotCount, final int verifyPeriod) {
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount");
        }
        this.slotCount = slotCount;
        this.words = slotCount == 0 ? new long[0] : new long[(slotCount + 63) >> 6];
        this.verifyPeriod = Math.max(1, verifyPeriod);
        this.untrusted = true;
    }

    public int slotCount() {
        return this.slotCount;
    }

    public int verifyCount() {
        return this.verifyCount;
    }

    public int missingNonEmptyDetections() {
        return this.missingNonEmptyDetections;
    }

    public boolean initialized() {
        return this.initialized;
    }

    public boolean untrusted() {
        return this.untrusted;
    }

    public void markUntrusted() {
        this.untrusted = true;
    }

    public void noteUse() {
        this.uses++;
    }

    public boolean shouldVerify() {
        if (!this.initialized || this.untrusted) {
            return true;
        }
        return this.uses > 0 && this.uses % this.verifyPeriod == 0;
    }

    public void setOccupancy(final int slot, final boolean nonEmpty) {
        if (slot < 0 || slot >= this.slotCount) {
            this.untrusted = true;
            return;
        }
        int word = slot >>> 6;
        long bit = 1L << (slot & 63);
        if (nonEmpty) {
            this.words[word] |= bit;
        } else {
            this.words[word] &= ~bit;
        }
    }

    public boolean test(final int slot) {
        if (slot < 0 || slot >= this.slotCount) {
            return false;
        }
        return (this.words[slot >>> 6] & (1L << (slot & 63))) != 0L;
    }

    public boolean anySet() {
        for (long word : this.words) {
            if (word != 0L) {
                return true;
            }
        }
        return false;
    }

    public void clearAll() {
        Arrays.fill(this.words, 0L);
        this.initialized = true;
        this.untrusted = false;
        this.uses = 0;
    }

    public void rebuild(final boolean[] occupied) {
        Arrays.fill(this.words, 0L);
        int n = Math.min(this.slotCount, occupied.length);
        for (int slot = 0; slot < n; slot++) {
            if (occupied[slot]) {
                this.words[slot >>> 6] |= 1L << (slot & 63);
            }
        }
        this.initialized = true;
        this.untrusted = false;
        this.uses = 0;
        this.verifyCount++;
    }

    public void rebuildFrom(final IntPredicate occupied) {
        boolean[] bits = new boolean[this.slotCount];
        for (int slot = 0; slot < this.slotCount; slot++) {
            bits[slot] = occupied.test(slot);
        }
        this.rebuild(bits);
    }

    /**
     * Compares the mask to a full occupancy scan. Missing non-empty slots are always rebuilt.
     * Extra empty bits are conservative and may be self-healed.
     */
    public VerifyResult verifyAgainst(final boolean[] actual) {
        this.verifyCount++;
        boolean missing = false;
        boolean extra = false;
        int n = Math.min(this.slotCount, actual.length);
        for (int slot = 0; slot < n; slot++) {
            boolean bit = this.test(slot);
            if (actual[slot] && !bit) {
                missing = true;
            } else if (!actual[slot] && bit) {
                extra = true;
            }
        }
        if (missing) {
            this.missingNonEmptyDetections++;
            this.rebuild(actual);
            return VerifyResult.MISSING_NONEMPTY;
        }
        if (extra) {
            this.rebuild(actual);
            return VerifyResult.EXTRA_EMPTY_HINT;
        }
        this.initialized = true;
        this.untrusted = false;
        this.uses = 0;
        return VerifyResult.MATCH;
    }

    /**
     * Walk set bits from lowest slot index to highest. Matches vanilla {@code for} order.
     */
    public void forEachSetBit(final IntConsumer consumer) {
        for (int w = 0; w < this.words.length; w++) {
            long word = this.words[w];
            int base = w << 6;
            while (word != 0L) {
                int bit = Long.numberOfTrailingZeros(word);
                int slot = base + bit;
                if (slot >= this.slotCount) {
                    return;
                }
                consumer.accept(slot);
                word &= word - 1L;
            }
        }
    }

    /**
     * Artificially clear a bit. Tests only — production mutation hooks must never do this without
     * a matching occupancy change.
     */
    public void injectMissingNonEmptyForTest(final int slot) {
        if (slot < 0 || slot >= this.slotCount) {
            return;
        }
        this.words[slot >>> 6] &= ~(1L << (slot & 63));
    }

    public enum VerifyResult {
        MATCH,
        EXTRA_EMPTY_HINT,
        MISSING_NONEMPTY
    }
}
