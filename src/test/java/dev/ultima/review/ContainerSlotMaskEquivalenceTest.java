package dev.ultima.review;

import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskMutationSources;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Differential checks for container non-empty slot masks against a vanilla-style occupancy scan.
 */
public final class ContainerSlotMaskEquivalenceTest {
    private ContainerSlotMaskEquivalenceTest() {
    }

    public static void run() {
        testMutationSourceTableIsComplete();
        testVanillaForOrderOnVariedFill();
        testLongArrayForMoreThan64Slots();
        testDifferentialRandomTrace();
        testConservativeVerificationCatchesMissingBit();
        testExtraEmptyBitsRemainSafeForIsEmpty();
        testInPlaceEmptyIsConservative();
        System.out.println("Container slot-mask equivalence checks passed.");
    }

    private static void testMutationSourceTableIsComplete() {
        if (SlotMaskMutationSources.VANILLA_26_2.size() < 20) {
            throw new AssertionError("mutation source table is missing vanilla paths");
        }
        if (SlotMaskMutationSources.trackedCount() < 10) {
            throw new AssertionError("too few tracked mutation paths");
        }
        boolean hopperLoopsUntouched = false;
        boolean inPlaceConservative = false;
        boolean moddedFallback = false;
        for (SlotMaskMutationSources.Source source : SlotMaskMutationSources.VANILLA_26_2) {
            if (source.hook().contains("does not rewrite hopper loops")
                    || source.hook().contains("no HopperBlockEntity extract/insert rewrite")) {
                hopperLoopsUntouched = true;
            }
            if (source.coverage() == SlotMaskMutationSources.Coverage.CONSERVATIVE
                    && source.vanillaPath().contains("ItemStack.shrink")) {
                inPlaceConservative = true;
            }
            if (source.coverage() == SlotMaskMutationSources.Coverage.FALLBACK
                    && source.vanillaPath().contains("Modded")) {
                moddedFallback = true;
            }
        }
        assertTrue(hopperLoopsUntouched, "hopper transfer loops must stay out of this module");
        assertTrue(inPlaceConservative, "in-place count changes must be conservative");
        assertTrue(moddedFallback, "modded containers must fall back to vanilla");
    }

    private static void testVanillaForOrderOnVariedFill() {
        int[] sizes = {1, 5, 9, 27, 54, 64};
        int[] patterns = {0, 1, 0b1010101, 0xFFFF, 0x80000001, Integer.MAX_VALUE};
        for (int size : sizes) {
            for (int pattern : patterns) {
                boolean[] occupied = new boolean[size];
                for (int slot = 0; slot < size; slot++) {
                    occupied[slot] = ((pattern >>> (slot & 31)) & 1) != 0;
                }
                assertOrder(occupied, "size=" + size + " pattern=" + pattern);
            }
            boolean[] everyOther = new boolean[size];
            for (int slot = 0; slot < size; slot += 2) {
                everyOther[slot] = true;
            }
            assertOrder(everyOther, "size=" + size + " every-other");
            boolean[] lastOnly = new boolean[size];
            lastOnly[size - 1] = true;
            assertOrder(lastOnly, "size=" + size + " last-only");
        }
    }

    private static void testLongArrayForMoreThan64Slots() {
        boolean[] occupied = new boolean[80];
        occupied[0] = true;
        occupied[63] = true;
        occupied[64] = true;
        occupied[79] = true;
        assertOrder(occupied, "80-slot mask crosses a long word");
        NonEmptySlotMask mask = maskFrom(occupied);
        List<Integer> slots = collect(mask);
        if (!slots.equals(List.of(0, 63, 64, 79))) {
            throw new AssertionError("word-crossing order: " + slots);
        }
    }

    private static void testDifferentialRandomTrace() {
        Random random = new Random(0x534C4F544D41534BL);
        int[] sizes = {5, 27, 54, 64, 80};
        for (int size : sizes) {
            boolean[] actual = new boolean[size];
            NonEmptySlotMask mask = new NonEmptySlotMask(size, 64);
            mask.rebuild(actual);
            for (int step = 0; step < 240; step++) {
                int op = random.nextInt(7);
                int slot = random.nextInt(size);
                switch (op) {
                    case 0 -> {
                        actual[slot] = true;
                        mask.setOccupancy(slot, true);
                    }
                    case 1 -> {
                        actual[slot] = false;
                        mask.setOccupancy(slot, false);
                    }
                    case 2 -> {
                        Arrays.fill(actual, false);
                        mask.clearAll();
                    }
                    case 3 -> {
                        for (int i = 0; i < size; i++) {
                            actual[i] = random.nextBoolean();
                        }
                        mask.rebuild(actual);
                    }
                    case 4 -> {
                        if (actual[slot]) {
                            actual[slot] = false;
                            // in-place empty: mask not cleared (conservative extra 1)
                        }
                    }
                    case 5 -> {
                        actual[slot] = true;
                        mask.setOccupancy(slot, true);
                    }
                    default -> {
                        actual[slot] = !actual[slot];
                        mask.setOccupancy(slot, actual[slot]);
                    }
                }
                assertConservativeSuperset(mask, actual, "size=" + size + " step=" + step);
                assertOrderAllowsExtraEmpties(mask, actual, "size=" + size + " step=" + step);
                if (step % 17 == 0) {
                    NonEmptySlotMask.VerifyResult result = mask.verifyAgainst(actual);
                    if (result == NonEmptySlotMask.VerifyResult.MISSING_NONEMPTY) {
                        throw new AssertionError("tracked trace must not miss a non-empty slot at step " + step);
                    }
                    assertBitsEqual(mask, actual, "after verify step " + step);
                }
            }
            mask.verifyAgainst(actual);
            assertBitsEqual(mask, actual, "final size=" + size);
        }
    }

    private static void testConservativeVerificationCatchesMissingBit() {
        boolean[] actual = new boolean[27];
        actual[3] = true;
        actual[11] = true;
        actual[26] = true;
        NonEmptySlotMask mask = maskFrom(actual);
        mask.injectMissingNonEmptyForTest(11);
        if (mask.test(11)) {
            throw new AssertionError("inject must clear bit 11");
        }
        NonEmptySlotMask.VerifyResult result = mask.verifyAgainst(actual);
        if (result != NonEmptySlotMask.VerifyResult.MISSING_NONEMPTY) {
            throw new AssertionError("verification must catch a missing non-empty slot, got " + result);
        }
        if (mask.missingNonEmptyDetections() < 1) {
            throw new AssertionError("missing-non-empty counter must increase");
        }
        assertBitsEqual(mask, actual, "rebuild after caught miss");
    }

    private static void testExtraEmptyBitsRemainSafeForIsEmpty() {
        boolean[] actual = new boolean[9];
        actual[2] = true;
        NonEmptySlotMask mask = maskFrom(actual);
        mask.setOccupancy(7, true);
        if (vanillaIsEmpty(actual) || simulatedIsEmpty(mask, actual)) {
            throw new AssertionError("occupied inventory must not look empty");
        }
        actual[2] = false;
        mask.setOccupancy(2, false);
        // bit 7 is extra empty
        if (!simulatedIsEmpty(mask, actual)) {
            throw new AssertionError("extra empty bits must not hide real emptiness");
        }
        if (mask.verifyAgainst(actual) != NonEmptySlotMask.VerifyResult.EXTRA_EMPTY_HINT) {
            throw new AssertionError("extra 1s are a conservative hint");
        }
    }

    private static void testInPlaceEmptyIsConservative() {
        boolean[] actual = {true, true, false};
        NonEmptySlotMask mask = maskFrom(actual);
        actual[1] = false;
        assertConservativeSuperset(mask, actual, "in-place empty");
        List<Integer> visited = collect(mask);
        if (!visited.equals(List.of(0, 1))) {
            throw new AssertionError("in-place empty still visits the stale bit in vanilla order: " + visited);
        }
        if (simulatedIsEmpty(mask, actual)) {
            throw new AssertionError("slot 0 is still full");
        }
    }

    private static void assertOrder(final boolean[] occupied, final String label) {
        NonEmptySlotMask mask = maskFrom(occupied);
        List<Integer> vanilla = vanillaOrder(occupied);
        List<Integer> fromMask = collect(mask);
        if (!vanilla.equals(fromMask)) {
            throw new AssertionError("order mismatch " + label + " vanilla=" + vanilla + " mask=" + fromMask);
        }
    }

    private static NonEmptySlotMask maskFrom(final boolean[] occupied) {
        NonEmptySlotMask mask = new NonEmptySlotMask(occupied.length);
        mask.rebuild(occupied);
        return mask;
    }

    private static List<Integer> vanillaOrder(final boolean[] occupied) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < occupied.length; i++) {
            if (occupied[i]) {
                slots.add(i);
            }
        }
        return slots;
    }

    private static List<Integer> collect(final NonEmptySlotMask mask) {
        List<Integer> slots = new ArrayList<>();
        mask.forEachSetBit(slots::add);
        return slots;
    }

    private static void assertConservativeSuperset(
            final NonEmptySlotMask mask, final boolean[] actual, final String label) {
        for (int slot = 0; slot < actual.length; slot++) {
            if (actual[slot] && !mask.test(slot)) {
                throw new AssertionError(label + ": mask missed non-empty slot " + slot);
            }
        }
    }

    private static void assertOrderAllowsExtraEmpties(
            final NonEmptySlotMask mask, final boolean[] actual, final String label) {
        List<Integer> vanilla = vanillaOrder(actual);
        List<Integer> fromMask = new ArrayList<>();
        mask.forEachSetBit(slot -> {
            if (actual[slot]) {
                fromMask.add(slot);
            }
        });
        if (!vanilla.equals(fromMask)) {
            throw new AssertionError(label + " filtered order mismatch vanilla=" + vanilla + " mask=" + fromMask);
        }
    }

    private static void assertBitsEqual(final NonEmptySlotMask mask, final boolean[] actual, final String label) {
        for (int slot = 0; slot < actual.length; slot++) {
            if (mask.test(slot) != actual[slot]) {
                throw new AssertionError(label + " bit " + slot + " mask=" + mask.test(slot) + " actual=" + actual[slot]);
            }
        }
    }

    private static boolean vanillaIsEmpty(final boolean[] actual) {
        for (boolean occupied : actual) {
            if (occupied) {
                return false;
            }
        }
        return true;
    }

    private static boolean simulatedIsEmpty(final NonEmptySlotMask mask, final boolean[] actual) {
        boolean[] found = {false};
        mask.forEachSetBit(slot -> {
            if (actual[slot]) {
                found[0] = true;
            }
        });
        return !found[0];
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
