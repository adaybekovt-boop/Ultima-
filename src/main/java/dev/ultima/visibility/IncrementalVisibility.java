package dev.ultima.visibility;

import java.util.BitSet;

/**
 * CPU retained/incremental visibility. Vanilla's visible-section list is the
 * oracle; this tracker only diffs and persists it. No approximate occlusion.
 */
public final class IncrementalVisibility {
    public enum Mode {
        FULL,
        INCREMENTAL
    }

    private final VisibilityMask previous = new VisibilityMask();
    private final VisibilityMask current = new VisibilityMask();
    private Mode mode = Mode.FULL;
    private int fullRebuilds;
    private int incrementalUpdates;
    private int stateChanges;
    private int considered;
    private long cpuNs;

    public void reset() {
        this.previous.ensureSlots(0);
        this.current.ensureSlots(0);
        this.previous.clear();
        this.current.clear();
        this.mode = Mode.FULL;
        this.fullRebuilds = 0;
        this.incrementalUpdates = 0;
        this.stateChanges = 0;
        this.considered = 0;
        this.cpuNs = 0L;
    }

    public Mode beginFrame(final int slotCount, final boolean forceFullRebuild) {
        long start = System.nanoTime();
        this.current.ensureSlots(slotCount);
        this.current.clear();
        this.considered = 0;
        this.stateChanges = 0;
        boolean sizeChanged = this.previous.slotCount() != slotCount;
        if (forceFullRebuild || sizeChanged) {
            this.previous.ensureSlots(slotCount);
            this.previous.clear();
            this.mode = Mode.FULL;
            this.fullRebuilds++;
        } else {
            this.mode = Mode.INCREMENTAL;
            this.incrementalUpdates++;
        }
        this.cpuNs += System.nanoTime() - start;
        return this.mode;
    }

    public void markVanillaVisible(final int slot) {
        this.current.setVisible(slot, true);
        this.considered++;
    }

    public boolean wasVisible(final int slot) {
        return this.previous.isVisible(slot);
    }

    public boolean isVisibleNow(final int slot) {
        return this.current.isVisible(slot);
    }

    public boolean needsOpaqueRecapture(final int slot) {
        return this.mode == Mode.FULL || !this.previous.isVisible(slot);
    }

    public void commit() {
        long start = System.nanoTime();
        BitSet prev = this.previous.snapshot();
        BitSet now = this.current.snapshot();
        BitSet delta = (BitSet)now.clone();
        delta.xor(prev);
        this.stateChanges = delta.cardinality();
        this.previous.replaceWith(now, this.current.slotCount());
        this.cpuNs += System.nanoTime() - start;
        VisibilityMetrics.record(
                this.cpuNs,
                this.considered,
                this.current.visibleCount(),
                this.stateChanges,
                this.mode == Mode.FULL ? 1 : 0,
                this.mode == Mode.INCREMENTAL ? 1 : 0);
        this.cpuNs = 0L;
    }

    public Mode mode() {
        return this.mode;
    }

    public int visibleCount() {
        return this.current.visibleCount();
    }

    public int stateChanges() {
        return this.stateChanges;
    }

    public int fullRebuilds() {
        return this.fullRebuilds;
    }

    public int incrementalUpdates() {
        return this.incrementalUpdates;
    }

    public VisibilityMask currentMask() {
        return this.current;
    }

    public boolean matchesOracle(final BitSet vanillaVisible, final int slotCount) {
        BitSet copy = new BitSet();
        this.current.copyInto(copy);
        BitSet oracle = (BitSet)vanillaVisible.clone();
        if (oracle.length() > slotCount) {
            oracle.clear(slotCount, oracle.length());
        }
        return copy.equals(oracle);
    }
}
