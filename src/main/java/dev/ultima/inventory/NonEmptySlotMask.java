package dev.ultima.inventory;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Conservative non-empty slot hint for inventories of any size.
 *
 * <p>Iteration visits set bits from least-significant to most-significant, matching
 * {@code for (int i = 0; i < size; i++)}. The mask is a hint until a rebuild or periodic
 * verification has matched a full occupancy scan. When the hint is untrusted, callers must
 * take the vanilla scan and rebuild.
 *
 * <p>False positives (bit set, slot empty) only waste work. False negatives (bit clear, slot
 * occupied) are forbidden while the mask is treated as trusted; an untrusted mask never skips
 * slots.
 */
public final class NonEmptySlotMask {
    public static final int VERIFY_PERIOD = 32;
    /** Vanilla double chests are 54 slots; player inventories are 43. 64 covers all vanilla. */
    public static final int SINGLE_WORD_LIMIT = 64;

    private long[] words = new long[1];
    private int size;
    private boolean trusted;
    private int usesSinceVerify;
    private int slotWriteDepth;
    private int verifyPeriod = VERIFY_PERIOD;

    public int size() {
        return this.size;
    }

    public boolean trusted() {
        return this.trusted;
    }

    public void setVerifyPeriod(final int verifyPeriod) {
        this.verifyPeriod = Math.max(1, verifyPeriod);
    }

    public int verifyPeriod() {
        return this.verifyPeriod;
    }

    /**
     * Bitwise occupancy, word 0 = slots 0–63. Used by differential tests.
     */
    public long[] snapshotWords() {
        return Arrays.copyOf(this.words, this.words.length);
    }

    public long snapshotWord0() {
        return this.words[0];
    }

    public void invalidate() {
        this.trusted = false;
        this.usesSinceVerify = 0;
    }

    public void enterSlotWrite() {
        this.slotWriteDepth++;
    }

    public void exitSlotWrite() {
        if (this.slotWriteDepth > 0) {
            this.slotWriteDepth--;
        }
    }

    /**
     * {@code setChanged} without a surrounding {@code setItem}/{@code removeItem} is how vanilla
     * publishes in-place {@code ItemStack.grow}/{@code shrink}. Those paths are not slot-indexed,
     * so the hint becomes untrusted until the next rebuild.
     */
    public void onUnindexedMutation() {
        if (this.slotWriteDepth == 0) {
            this.invalidate();
        }
    }

    public void onSlotOccupancy(final int slot, final boolean occupied) {
        if (slot < 0 || this.size <= 0 || slot >= this.size) {
            this.invalidate();
            return;
        }
        if (!this.trusted) {
            return;
        }
        setBit(this.words, slot, occupied);
    }

    public void onCleared() {
        Arrays.fill(this.words, 0L);
        this.trusted = this.size > 0;
        this.usesSinceVerify = 0;
    }

    public void rebuild(final SlotOccupancy occupancy) {
        this.rebuildUnchecked(occupancy);
    }

    /**
     * @return {@code true} if every bit matches a full occupancy scan
     */
    public boolean matches(final SlotOccupancy occupancy) {
        if (occupancy.size() != this.size) {
            return false;
        }
        long[] expected = scan(occupancy);
        return Arrays.equals(expected, this.words);
    }

    /**
     * Rebuilds when untrusted. Periodically re-scans even when trusted so an untracked mutation
     * cannot remain a false negative.
     *
     * @return {@code true} if the mask may be used to skip empty slots
     */
    public boolean prepareTrustedHint(final SlotOccupancy occupancy) {
        return this.prepareTrustedHintUnchecked(occupancy);
    }

    /**
     * Empty-only decision: {@link Boolean#TRUE} means vanilla would also see every slot empty.
     * {@code null} means the caller must scan.
     */
    public Boolean tryExactEmpty(final SlotOccupancy occupancy) {
        this.prepareTrustedHintUnchecked(occupancy);
        if (!this.trusted) {
            return null;
        }
        return !anyBitSet();
    }

    /**
     * Visits occupied slots in vanilla index order. Falls back to a full scan when the hint is
     * untrusted or a periodic verify fails. Returns {@code true} when the hint path was used.
     */
    public boolean forEachOccupied(final SlotOccupancy occupancy, final IntConsumer visitor) {
        if (!this.prepareTrustedHintUnchecked(occupancy)) {
            scanVisit(occupancy, visitor);
            return false;
        }
        try {
            for (int wordIndex = 0; wordIndex < this.words.length; wordIndex++) {
                long bits = this.words[wordIndex];
                while (bits != 0L) {
                    int bit = Long.numberOfTrailingZeros(bits);
                    int slot = (wordIndex << 6) + bit;
                    bits &= bits - 1L;
                    if (slot >= this.size) {
                        break;
                    }
                    if (occupancy.isOccupied(slot)) {
                        visitor.accept(slot);
                    } else {
                        setBit(this.words, slot, false);
                    }
                }
            }
            return true;
        } catch (Throwable error) {
            // Do not scanVisit: the visitor may already have mutated earlier slots.
            this.invalidate();
            throw error;
        }
    }

    /**
     * Same order as {@code for (int i = 0; i < size; i++)} over set bits, without touching the
     * backing inventory. Tests use this to prove order-safety of the bit walk itself.
     */
    public void forEachSetBit(final IntConsumer visitor) {
        for (int wordIndex = 0; wordIndex < this.words.length; wordIndex++) {
            long bits = this.words[wordIndex];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                int slot = (wordIndex << 6) + bit;
                bits &= bits - 1L;
                if (slot >= this.size) {
                    break;
                }
                visitor.accept(slot);
            }
        }
    }

    /**
     * Test-only: flip a bit without going through mutation hooks, simulating a missed update.
     */
    public void corruptBitForTest(final int slot, final boolean occupied) {
        if (slot < 0 || slot >= this.size) {
            return;
        }
        setBit(this.words, slot, occupied);
    }

    public boolean anyBitSet() {
        for (long word : this.words) {
            if (word != 0L) {
                return true;
            }
        }
        return false;
    }

    public boolean hintedOccupied(final int slot) {
        if (slot < 0 || slot >= this.size || !this.trusted) {
            return false;
        }
        int wordIndex = slot >>> 6;
        if (wordIndex >= this.words.length) {
            return false;
        }
        return (this.words[wordIndex] & (1L << (slot & 63))) != 0L;
    }

    public static long[] scan(final SlotOccupancy occupancy) {
        int size = occupancy.size();
        long[] words = new long[wordCount(size)];
        for (int slot = 0; slot < size; slot++) {
            if (occupancy.isOccupied(slot)) {
                setBit(words, slot, true);
            }
        }
        return words;
    }

    public static int wordCount(final int size) {
        return size <= 0 ? 1 : (size + 63) >> 6;
    }

    private static void setBit(final long[] words, final int slot, final boolean occupied) {
        int wordIndex = slot >>> 6;
        long bit = 1L << (slot & 63);
        if (occupied) {
            words[wordIndex] |= bit;
        } else {
            words[wordIndex] &= ~bit;
        }
    }

    private void rebuildUnchecked(final SlotOccupancy occupancy) {
        int nextSize = occupancy.size();
        if (nextSize < 0) {
            this.size = 0;
            this.words = new long[1];
            this.trusted = false;
            return;
        }
        this.size = nextSize;
        int wordCount = wordCount(nextSize);
        if (this.words.length != wordCount) {
            this.words = new long[wordCount];
        } else {
            Arrays.fill(this.words, 0L);
        }
        for (int slot = 0; slot < nextSize; slot++) {
            if (occupancy.isOccupied(slot)) {
                setBit(this.words, slot, true);
            }
        }
        this.trusted = true;
        this.usesSinceVerify = 0;
    }

    private boolean prepareTrustedHintUnchecked(final SlotOccupancy occupancy) {
        if (occupancy.size() != this.size || !this.trusted) {
            this.rebuildUnchecked(occupancy);
            return this.trusted;
        }
        this.usesSinceVerify++;
        if (this.usesSinceVerify >= this.verifyPeriod) {
            if (!this.matches(occupancy)) {
                this.rebuildUnchecked(occupancy);
                return false;
            }
            this.usesSinceVerify = 0;
        }
        return this.trusted;
    }

    private static void scanVisit(final SlotOccupancy occupancy, final IntConsumer visitor) {
        int size = occupancy.size();
        for (int slot = 0; slot < size; slot++) {
            if (occupancy.isOccupied(slot)) {
                visitor.accept(slot);
            }
        }
    }
}
