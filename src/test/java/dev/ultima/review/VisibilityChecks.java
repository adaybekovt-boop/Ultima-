package dev.ultima.review;

import dev.ultima.visibility.IncrementalVisibility;
import dev.ultima.visibility.VisibilityMetrics;
import dev.ultima.visibility.gpu.GpuVisibilityContract;
import java.util.BitSet;
import java.util.Random;

final class VisibilityChecks {
    private VisibilityChecks() {
    }

    static void run() {
        testStationaryNoChanges();
        testYawPitchTranslation();
        testTeleport();
        testChunkLoadUnload();
        testRenderDistanceAndDimension();
        testOracleEquality();
        testGpuContractFeedsMask();
        testMetrics();
    }

    private static void testStationaryNoChanges() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        int[] slots = {1, 4, 7, 9};
        apply(tracker, 16, true, slots);
        tracker.beginFrame(16, false);
        for (int slot : slots) {
            tracker.markVanillaVisible(slot);
        }
        if (tracker.mode() != IncrementalVisibility.Mode.INCREMENTAL) {
            throw new AssertionError("stationary second frame is incremental");
        }
        if (tracker.needsOpaqueRecapture(4)) {
            throw new AssertionError("still-visible slot must not force recapture");
        }
        tracker.commit();
        if (tracker.stateChanges() != 0) {
            throw new AssertionError("stationary visibility must not change");
        }
    }

    private static void testYawPitchTranslation() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 32, true, 0, 1, 2, 3);
        tracker.beginFrame(32, false);
        for (int slot : new int[] {1, 2, 3, 4}) {
            tracker.markVanillaVisible(slot);
        }
        if (!tracker.needsOpaqueRecapture(4) || tracker.needsOpaqueRecapture(2)) {
            throw new AssertionError("only newly visible slots recapture");
        }
        tracker.commit();
        if (tracker.stateChanges() != 2) {
            throw new AssertionError("yaw/translation should drop 0 and add 4, changes=" + tracker.stateChanges());
        }
        apply(tracker, 32, false, 1, 2, 3, 4, 8);
        if (!tracker.isVisibleNow(8)) {
            throw new AssertionError("pitch-style add");
        }
    }

    private static void testTeleport() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 64, true, 0, 1, 2);
        apply(tracker, 64, false, 50, 51, 52);
        if (tracker.visibleCount() != 3 || tracker.isVisibleNow(0) || !tracker.isVisibleNow(50)) {
            throw new AssertionError("teleport replaces occupancy");
        }
        if (tracker.stateChanges() != 6) {
            throw new AssertionError("disjoint teleport is 3 drops + 3 adds, got " + tracker.stateChanges());
        }
    }

    private static void testChunkLoadUnload() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 16, true, 2, 3);
        tracker.beginFrame(16, false);
        tracker.markVanillaVisible(2);
        tracker.markVanillaVisible(3);
        tracker.markVanillaVisible(15);
        if (!tracker.needsOpaqueRecapture(15) || tracker.needsOpaqueRecapture(2)) {
            throw new AssertionError("loaded chunk slot is newly visible");
        }
        tracker.commit();
        apply(tracker, 16, false, 2);
        if (tracker.isVisibleNow(3) || tracker.isVisibleNow(15) || !tracker.isVisibleNow(2)) {
            throw new AssertionError("unloaded slots must leave the oracle set");
        }
    }

    private static void testRenderDistanceAndDimension() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 8, true, 0, 1);
        IncrementalVisibility.Mode grown = apply(tracker, 32, false, 0, 1, 20);
        if (grown != IncrementalVisibility.Mode.FULL) {
            throw new AssertionError("render-distance slotCount change is a full rebuild");
        }
        tracker.reset();
        IncrementalVisibility.Mode dimension = apply(tracker, 8, true, 4);
        if (dimension != IncrementalVisibility.Mode.FULL || tracker.fullRebuilds() != 1) {
            throw new AssertionError("dimension/reset starts with a full rebuild");
        }
    }

    private static void testOracleEquality() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        Random random = new Random(0x5649534942L);
        int slots = 48;
        BitSet oracle = new BitSet();
        apply(tracker, slots, true);
        for (int frame = 0; frame < 200; frame++) {
            oracle.clear();
            int count = random.nextInt(12);
            int[] visible = new int[count];
            for (int i = 0; i < count; i++) {
                int slot = random.nextInt(slots);
                oracle.set(slot);
                visible[i] = slot;
            }
            apply(tracker, slots, false, unique(oracle, slots));
            if (!tracker.matchesOracle(oracle, slots)) {
                throw new AssertionError("tracker must equal vanilla visible set at frame " + frame);
            }
        }
    }

    private static void testGpuContractFeedsMask() {
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 8, true, 1, 3, 6);
        BitSet applied = new BitSet();
        GpuVisibilityContract.transfer(
                (dest, slotCount) -> tracker.currentMask().copyInto(dest),
                (visible, slotCount) -> {
                    applied.clear();
                    applied.or(visible);
                },
                8);
        if (!applied.get(1) || !applied.get(3) || !applied.get(6) || applied.get(2)) {
            throw new AssertionError("GPU contract must copy the CPU oracle mask");
        }
    }

    private static void testMetrics() {
        VisibilityMetrics.reset();
        IncrementalVisibility tracker = new IncrementalVisibility();
        apply(tracker, 4, true, 0, 1);
        apply(tracker, 4, false, 1, 2);
        VisibilityMetrics.Snapshot snapshot = VisibilityMetrics.snapshot();
        if (snapshot.fullRebuilds() < 1 || snapshot.incrementalUpdates() < 1 || snapshot.sectionsVisible() != 2) {
            throw new AssertionError("visibility metrics " + snapshot);
        }
    }

    private static IncrementalVisibility.Mode apply(
            final IncrementalVisibility tracker,
            final int slotCount,
            final boolean forceFull,
            final int... slots) {
        IncrementalVisibility.Mode mode = tracker.beginFrame(slotCount, forceFull);
        for (int slot : slots) {
            tracker.markVanillaVisible(slot);
        }
        tracker.commit();
        return mode;
    }

    private static int[] unique(final BitSet bits, final int slotCount) {
        int[] slots = new int[bits.cardinality()];
        int i = 0;
        int bit = bits.nextSetBit(0);
        while (bit >= 0 && bit < slotCount) {
            slots[i++] = bit;
            bit = bits.nextSetBit(bit + 1);
        }
        return slots;
    }
}
