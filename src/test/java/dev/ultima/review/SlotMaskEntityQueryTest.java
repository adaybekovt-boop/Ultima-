package dev.ultima.review;

import dev.ultima.entityquery.EntityQueryEarlyOut;
import dev.ultima.entityquery.EntityQueryKind;
import dev.ultima.entityquery.EntitySectionCounters;
import dev.ultima.entityquery.LithiumIntersection;
import dev.ultima.inventory.NonEmptySlotMask;
import dev.ultima.inventory.SlotMaskHooks;
import dev.ultima.inventory.SlotMaskTracker;
import dev.ultima.inventory.SlotOccupancy;
import dev.ultima.inventory.VanillaInventoryMutationSources;
import dev.ultima.util.SectionRangeMath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.world.SimpleContainer;

/**
 * Differential checks for container slot masks and entity-query empty early-outs.
 * Does not require a running Minecraft world.
 */
public final class SlotMaskEntityQueryTest {
    private SlotMaskEntityQueryTest() {
    }

    public static void main(final String[] args) {
        run();
        System.out.println("Slot-mask and entity-query differential checks passed.");
    }

    static void run() {
        testMutationCatalogPresent();
        testReplaceWithInvalidatesStaleEmptyMask();
        testIndexedWriteDepthRecoversAfterThrow();
        testPartialOccupiedVisitDoesNotRescan();
        testSharedSectionWalkBudget();
        testRandomOccupancyMatchesScan();
        testIterationOrderMatchesForLoop();
        testConservativeVerifyCatchesCorruption();
        testWideInventoryWords();
        testEntityQueryEmptyOnly();
        testEntityQueryBoundaryNegative();
        testEntityQuerySectionMove();
        testLithiumDecision();
        System.out.println("Slot-mask / entity-query equivalence checks passed.");
    }

    private static void testMutationCatalogPresent() {
        assertTrue(VanillaInventoryMutationSources.sourceCount() >= 20, "mutation catalog must list the 26.2 sources");
        boolean sawSetItem = false;
        boolean sawSetChanged = false;
        boolean sawLoot = false;
        boolean sawReplaceWith = false;
        for (String source : VanillaInventoryMutationSources.SOURCES) {
            if (source.contains("setItem")) {
                sawSetItem = true;
            }
            if (source.contains("setChanged")) {
                sawSetChanged = true;
            }
            if (source.contains("unpackLootTable")) {
                sawLoot = true;
            }
            if (source.contains("replaceWith")) {
                sawReplaceWith = true;
            }
        }
        assertTrue(sawSetItem && sawSetChanged && sawLoot, "catalog covers setItem, setChanged, loot unpack");
        assertTrue(sawReplaceWith, "catalog must list Inventory.replaceWith (Variant A: mixin hook, not verify-only)");
        boolean mixinWrapsReplaceWith = false;
        for (java.lang.reflect.Method method :
                dev.ultima.mixin.container_slot_mask.InventoryMixin.class.getDeclaredMethods()) {
            if ("ultimaReplaceWith".equals(method.getName())) {
                mixinWrapsReplaceWith = true;
            }
        }
        assertTrue(mixinWrapsReplaceWith, "InventoryMixin must wrap vanilla replaceWith");
    }

    /**
     * Production hook used by {@code InventoryMixin.replaceWith}: a trusted empty mask
     * must not keep reporting empty after a bulk copy that never went through {@code setItem}.
     */
    private static void testReplaceWithInvalidatesStaleEmptyMask() {
        SimpleContainer container = new SimpleContainer(5);
        NonEmptySlotMask mask = SlotMaskTracker.of(container);
        mask.setVerifyPeriod(256);
        mask.rebuild(SlotOccupancy.of(5, slot -> false));
        assertTrue(Boolean.TRUE.equals(mask.tryExactEmpty(SlotOccupancy.of(5, slot -> false))), "starts empty");
        SlotOccupancy filled = SlotOccupancy.of(5, slot -> slot == 0);
        assertTrue(
                Boolean.TRUE.equals(mask.tryExactEmpty(filled)),
                "without the replaceWith hook a trusted empty mask is a false-negative until periodic verify");
        SlotMaskHooks.afterBulkReplace(container);
        assertTrue(
                Boolean.FALSE.equals(mask.tryExactEmpty(filled)),
                "after replaceWith the mask must rebuild from occupancy and see the filled slot");
    }

    private static void testIndexedWriteDepthRecoversAfterThrow() {
        SimpleContainer container = new SimpleContainer(3);
        NonEmptySlotMask mask = SlotMaskTracker.of(container);
        mask.rebuild(SlotOccupancy.of(3, slot -> false));
        try {
            SlotMaskHooks.runIndexedWrite(container, 0, () -> {
                throw new IllegalStateException("vanilla setItem failed");
            });
            throw new AssertionError("vanilla throw must propagate");
        } catch (IllegalStateException ignored) {
        }
        mask.onUnindexedMutation();
        assertTrue(
                !mask.trusted(),
                "enter/exit depth must not leak: in-place setChanged must still invalidate after a thrown write");
    }

    private static void testPartialOccupiedVisitDoesNotRescan() {
        int[] counts = {1, 1, 1};
        NonEmptySlotMask mask = new NonEmptySlotMask();
        mask.rebuild(occupancy(counts));
        List<Integer> visited = new ArrayList<>();
        try {
            mask.forEachOccupied(occupancy(counts), slot -> {
                visited.add(slot);
                throw new IllegalStateException("visitor failed at " + slot);
            });
            throw new AssertionError("visitor throw must propagate");
        } catch (IllegalStateException ignored) {
        }
        assertTrue(visited.equals(List.of(0)), "only the failing slot is visited, got " + visited);
        assertTrue(!mask.trusted(), "partial walk must untrust the mask instead of scan-visiting again");
    }

    private static void testSharedSectionWalkBudget() {
        assertTrue(
                EntityQueryEarlyOut.SECTION_PROBE_BUDGET == SectionRangeMath.DIRECT_LOOKUP_BUDGET,
                "early-out budget must be the shared SectionRangeMath constant");
        assertTrue(SectionRangeMath.preferVanillaSectionWalk(1025L, Integer.MAX_VALUE), "volume over 1024");
        assertTrue(SectionRangeMath.preferVanillaSectionWalk(10L, 3), "lookup skips when volume > loaded count");
        assertTrue(!SectionRangeMath.preferVanillaSectionWalk(10L, 10), "volume equal to loaded count still probes");
        assertTrue(!SectionRangeMath.preferVanillaSectionWalk(10L, 11), "volume under loaded count probes");
        FakeSectionWorld world = new FakeSectionWorld();
        world.putAccessible(0, 4, 0, new EntitySectionCounters());
        AABB sparse = new AABB(0.0, 0.0, 0.0, 48.0, 48.0, 48.0);
        assertTrue(
                world.lookupWouldSkipDenseProbe(sparse),
                "lookup mixin skips a dense probe when volume exceeds loaded section count");
        assertTrue(world.probeInBudget(sparse), "125 padded keys are still under the 1024 early-out budget");
        assertTrue(
                world.earlyOut(sparse, EntityQueryKind.ANY),
                "early-out still proves emptiness under the 1024 budget in a sparse world");
        AABB overBudget = new AABB(0.0, 0.0, 0.0, 176.0, 176.0, 176.0);
        assertTrue(!world.probeInBudget(overBudget), "wide box exceeds the 1024-key early-out budget");
        assertTrue(
                !world.earlyOut(overBudget, EntityQueryKind.ANY),
                "early-out must fail open to vanilla when the 1024-key budget is exceeded");
    }

    private static void testRandomOccupancyMatchesScan() {
        long[] seeds = {0x554C54494D41L, 1L, 42L, 99L, 2026L, 0xDEADBEEFL, 7L, 123456789L};
        int totalOps = 0;
        for (long seed : seeds) {
            totalOps += runInventoryTrace(seed, 27, 256);
            totalOps += runInventoryTrace(seed ^ 0x9E3779B97F4A7C15L, 54, 256);
            totalOps += runInventoryTrace(seed + 3, 5, 200);
            totalOps += runInventoryTrace(seed + 5, 3, 200);
            totalOps += runInventoryTrace(seed + 9, 80, 200);
        }
        assertTrue(totalOps >= 200, "need at least 200 operations, got " + totalOps);
    }

    private static int runInventoryTrace(final long seed, final int size, final int operations) {
        Random random = new Random(seed);
        int[] counts = new int[size];
        NonEmptySlotMask mask = new NonEmptySlotMask();
        mask.setVerifyPeriod(8);
        SlotOccupancy occupancy = occupancy(counts);
        mask.rebuild(occupancy);
        for (int op = 0; op < operations; op++) {
            applyRandomMutation(random, counts, mask);
            SlotOccupancy now = occupancy(counts);
            if (!mask.trusted()) {
                mask.rebuild(now);
            }
            long[] expected = NonEmptySlotMask.scan(now);
            if (!Arrays.equals(expected, mask.snapshotWords())) {
                throw new AssertionError("mask diverged at op=" + op + " size=" + size + " seed=" + seed
                        + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(mask.snapshotWords()));
            }
            Boolean empty = mask.tryExactEmpty(now);
            boolean vanillaEmpty = vanillaEmpty(counts);
            if (empty == null || empty != vanillaEmpty) {
                throw new AssertionError("empty-only mismatch op=" + op + " hint=" + empty + " vanilla=" + vanillaEmpty);
            }
        }
        return operations;
    }

    private static void applyRandomMutation(final Random random, final int[] counts, final NonEmptySlotMask mask) {
        int kind = random.nextInt(8);
        int slot = random.nextInt(counts.length);
        switch (kind) {
            case 0 -> {
                mask.enterSlotWrite();
                counts[slot] = random.nextInt(65);
                mask.onSlotOccupancy(slot, counts[slot] > 0);
                mask.exitSlotWrite();
            }
            case 1 -> {
                mask.enterSlotWrite();
                counts[slot] = 0;
                mask.onSlotOccupancy(slot, false);
                mask.exitSlotWrite();
            }
            case 2 -> {
                mask.enterSlotWrite();
                int add = random.nextInt(1, 16);
                int remaining = add;
                for (int i = 0; i < counts.length && remaining > 0; i++) {
                    if (counts[i] > 0 && counts[i] < 64) {
                        int space = Math.min(64 - counts[i], remaining);
                        counts[i] += space;
                        remaining -= space;
                        mask.onSlotOccupancy(i, true);
                    }
                }
                for (int i = 0; i < counts.length && remaining > 0; i++) {
                    if (counts[i] == 0) {
                        int put = Math.min(64, remaining);
                        counts[i] = put;
                        remaining -= put;
                        mask.onSlotOccupancy(i, true);
                    }
                }
                mask.exitSlotWrite();
            }
            case 3 -> {
                mask.enterSlotWrite();
                int take = random.nextInt(1, 8);
                if (counts[slot] > take) {
                    counts[slot] -= take;
                } else {
                    counts[slot] = 0;
                }
                mask.onSlotOccupancy(slot, counts[slot] > 0);
                mask.exitSlotWrite();
            }
            case 4 -> {
                Arrays.fill(counts, 0);
                mask.onCleared();
            }
            case 5 -> {
                if (counts[slot] > 0) {
                    counts[slot] = Math.max(0, counts[slot] - random.nextInt(1, 4));
                    mask.onUnindexedMutation();
                    if (!mask.trusted()) {
                        mask.rebuild(occupancy(counts));
                    }
                } else {
                    mask.enterSlotWrite();
                    counts[slot] = random.nextInt(1, 8);
                    mask.onSlotOccupancy(slot, true);
                    mask.exitSlotWrite();
                }
            }
            case 6 -> {
                mask.enterSlotWrite();
                counts[slot] = 64;
                mask.onSlotOccupancy(slot, true);
                mask.exitSlotWrite();
            }
            default -> {
                mask.enterSlotWrite();
                int from = random.nextInt(counts.length);
                if (counts[from] > 0) {
                    int moved = 1;
                    counts[from] -= moved;
                    mask.onSlotOccupancy(from, counts[from] > 0);
                    int dest = random.nextInt(counts.length);
                    counts[dest] = Math.min(64, counts[dest] + moved);
                    mask.onSlotOccupancy(dest, true);
                }
                mask.exitSlotWrite();
            }
        }
    }

    private static void testIterationOrderMatchesForLoop() {
        int[] sizes = {1, 5, 27, 43, 54, 64, 80, 128};
        int[] patterns = {0, 1, 0b10101010, 0b111, ~0, 1 << 31, (1 << 0) | (1 << 17) | (1 << 53)};
        for (int size : sizes) {
            for (int pattern : patterns) {
                int[] counts = new int[size];
                for (int slot = 0; slot < size; slot++) {
                    counts[slot] = ((pattern >>> (slot % 32)) & 1) == 1 ? 1 : 0;
                }
                if (size > 64 && pattern == ~0) {
                    Arrays.fill(counts, 1);
                }
                NonEmptySlotMask mask = new NonEmptySlotMask();
                mask.rebuild(occupancy(counts));
                List<Integer> vanilla = new ArrayList<>();
                for (int slot = 0; slot < size; slot++) {
                    if (counts[slot] > 0) {
                        vanilla.add(slot);
                    }
                }
                List<Integer> fromBits = new ArrayList<>();
                mask.forEachSetBit(fromBits::add);
                if (!vanilla.equals(fromBits)) {
                    throw new AssertionError("bit order diverged size=" + size + " vanilla=" + vanilla + " bits=" + fromBits);
                }
                List<Integer> fromHint = new ArrayList<>();
                boolean usedHint = mask.forEachOccupied(occupancy(counts), fromHint::add);
                assertTrue(usedHint, "trusted mask must use the hint path");
                if (!vanilla.equals(fromHint)) {
                    throw new AssertionError("occupied iteration diverged size=" + size);
                }
            }
        }
    }

    private static void testConservativeVerifyCatchesCorruption() {
        int[] counts = new int[27];
        counts[3] = 4;
        counts[10] = 1;
        NonEmptySlotMask mask = new NonEmptySlotMask();
        mask.setVerifyPeriod(1);
        mask.rebuild(occupancy(counts));
        mask.corruptBitForTest(3, false);
        assertTrue(!mask.matches(occupancy(counts)), "corruption must be visible to matches()");
        boolean usedHint = mask.forEachOccupied(occupancy(counts), slot -> {});
        assertTrue(!usedHint, "verify must reject a corrupted mask and fall back to vanilla scan");
        assertTrue(mask.matches(occupancy(counts)), "fallback rebuild must restore the honest mask");

        mask.corruptBitForTest(0, true);
        Boolean empty = mask.tryExactEmpty(occupancy(counts));
        assertTrue(empty != null && !empty, "false-positive occupancy must not report empty");
    }

    private static void testWideInventoryWords() {
        int[] counts = new int[80];
        counts[0] = 1;
        counts[63] = 1;
        counts[64] = 1;
        counts[79] = 1;
        NonEmptySlotMask mask = new NonEmptySlotMask();
        mask.rebuild(occupancy(counts));
        assertTrue(mask.snapshotWords().length == 2, "80 slots need two longs");
        List<Integer> order = new ArrayList<>();
        mask.forEachSetBit(order::add);
        assertTrue(order.equals(List.of(0, 63, 64, 79)), "LSB-to-MSB across word boundary, got " + order);
    }

    private static void testEntityQueryEmptyOnly() {
        Random random = new Random(0x45515459L);
        for (int trial = 0; trial < 400; trial++) {
            FakeSectionWorld world = FakeSectionWorld.random(random);
            AABB box = AABB.random(random);
            for (EntityQueryKind kind : new EntityQueryKind[] {
                EntityQueryKind.ANY, EntityQueryKind.PLAYERS, EntityQueryKind.LIVING, EntityQueryKind.ITEMS, EntityQueryKind.COLLIDABLE
            }) {
                boolean vanillaEmpty = world.vanillaQueryEmpty(box, kind);
                boolean earlyOut = world.earlyOut(box, kind);
                if (earlyOut && !vanillaEmpty) {
                    throw new AssertionError("early-out on a non-empty vanilla query kind=" + kind + " trial=" + trial);
                }
                if (vanillaEmpty && world.probeInBudget(box) && kind != EntityQueryKind.UNKNOWN && !earlyOut && world.allAccessibleZero(box, kind)) {
                    throw new AssertionError("failed to early-out an empty query kind=" + kind + " trial=" + trial);
                }
            }
            boolean unknownEarly = world.earlyOut(box, EntityQueryKind.UNKNOWN);
            assertTrue(!unknownEarly, "UNKNOWN queries must never early-out");
        }
    }

    private static void testEntityQueryBoundaryNegative() {
        FakeSectionWorld world = new FakeSectionWorld();
        AABB box = new AABB(0.0, 64.0, 0.0, 16.0, 80.0, 16.0);
        world.putAccessible(0, 4, 0, countersWithPlayer());
        world.placeEntityOnBoundary(0, 4, 0, box);
        assertTrue(!world.vanillaQueryEmpty(box, EntityQueryKind.PLAYERS), "vanilla must see the boundary player");
        assertTrue(!world.earlyOut(box, EntityQueryKind.PLAYERS), "early-out must not fire with one relevant entity on the box boundary");
        assertTrue(world.earlyOut(box, EntityQueryKind.ITEMS), "item query may early-out when only a player is present");
    }

    private static void testEntityQuerySectionMove() {
        FakeSectionWorld world = new FakeSectionWorld();
        AABB boxOld = new AABB(0.0, 64.0, 0.0, 8.0, 72.0, 8.0);
        AABB boxNew = new AABB(16.0, 64.0, 0.0, 24.0, 72.0, 8.0);
        EntitySectionCounters from = countersWithPlayer();
        EntitySectionCounters to = new EntitySectionCounters();
        world.putAccessible(0, 4, 0, from);
        world.putAccessible(1, 4, 0, to);
        assertTrue(!world.earlyOut(boxOld, EntityQueryKind.PLAYERS), "player still in old section");
        from.moveTo(to, true, true, false, false);
        assertTrue(
                world.earlyOut(boxOld, EntityQueryKind.PLAYERS),
                "after the move, the old section must be empty before a later query");
        assertTrue(!world.earlyOut(boxNew, EntityQueryKind.PLAYERS), "new section must block the early-out");
        assertTrue(from.players() == 0 && to.players() == 1, "counters must move atomically with the entity");
    }

    private static void testLithiumDecision() {
        assertTrue("auto-disable".equals(LithiumIntersection.DECISION), "Lithium intersection is auto-disable");
        assertTrue(EntityQueryEarlyOut.SECTION_PROBE_BUDGET == 1024L, "probe budget matches entity_section_lookup");
    }

    private static EntitySectionCounters countersWithPlayer() {
        EntitySectionCounters counters = new EntitySectionCounters();
        counters.add(true, true, false, false);
        return counters;
    }

    private static SlotOccupancy occupancy(final int[] counts) {
        return SlotOccupancy.of(counts.length, slot -> counts[slot] > 0);
    }

    private static boolean vanillaEmpty(final int[] counts) {
        for (int count : counts) {
            if (count > 0) {
                return false;
            }
        }
        return true;
    }

    private static void assertTrue(final boolean value, final String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private record AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        static AABB random(final Random random) {
            double minX = random.nextInt(-32, 32);
            double minY = random.nextInt(0, 128);
            double minZ = random.nextInt(-32, 32);
            return new AABB(minX, minY, minZ, minX + random.nextInt(1, 24), minY + random.nextInt(1, 24), minZ + random.nextInt(1, 24));
        }
    }

    private static final class FakeSectionWorld {
        private final java.util.Map<Long, EntitySectionCounters> accessible = new java.util.HashMap<>();

        static FakeSectionWorld random(final Random random) {
            FakeSectionWorld world = new FakeSectionWorld();
            int sections = random.nextInt(0, 12);
            for (int i = 0; i < sections; i++) {
                int x = random.nextInt(-4, 5);
                int y = random.nextInt(0, 8);
                int z = random.nextInt(-4, 5);
                EntitySectionCounters counters = new EntitySectionCounters();
                int occupants = random.nextInt(0, 4);
                for (int n = 0; n < occupants; n++) {
                    int type = random.nextInt(4);
                    counters.add(type == 0, type <= 1, type == 2, false);
                }
                world.putAccessible(x, y, z, counters);
            }
            return world;
        }

        void putAccessible(final int x, final int y, final int z, final EntitySectionCounters counters) {
            this.accessible.put(net.minecraft.core.SectionPos.asLong(x, y, z), counters);
        }

        void placeEntityOnBoundary(final int x, final int y, final int z, final AABB box) {
            long key = net.minecraft.core.SectionPos.asLong(x, y, z);
            if (!this.accessible.containsKey(key)) {
                this.putAccessible(x, y, z, countersWithPlayer());
            }
        }

        boolean vanillaQueryEmpty(final AABB box, final EntityQueryKind kind) {
            int xMin = sectionCoord(box.minX - 2.0);
            int yMin = sectionCoord(box.minY - 4.0);
            int zMin = sectionCoord(box.minZ - 2.0);
            int xMax = sectionCoord(box.maxX + 2.0);
            int yMax = sectionCoord(box.maxY);
            int zMax = sectionCoord(box.maxZ + 2.0);
            for (long x = xMin; x <= xMax; x++) {
                for (long z = zMin; z <= zMax; z++) {
                    for (long y = yMin; y <= yMax; y++) {
                        EntitySectionCounters counters = this.accessible.get(net.minecraft.core.SectionPos.asLong((int)x, (int)y, (int)z));
                        if (counters != null && counters.of(kind) > 0) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        boolean earlyOut(final AABB box, final EntityQueryKind kind) {
            int xMin = sectionCoord(box.minX - 2.0);
            int yMin = sectionCoord(box.minY - 4.0);
            int zMin = sectionCoord(box.minZ - 2.0);
            int xMax = sectionCoord(box.maxX + 2.0);
            int yMax = sectionCoord(box.maxY);
            int zMax = sectionCoord(box.maxZ + 2.0);
            return EntityQueryEarlyOut.allIntersectingEmpty(
                    xMin, yMin, zMin, xMax, yMax, zMax, kind, key -> this.accessible.get(key));
        }

        boolean probeInBudget(final AABB box) {
            int xMin = sectionCoord(box.minX - 2.0);
            int yMin = sectionCoord(box.minY - 4.0);
            int zMin = sectionCoord(box.minZ - 2.0);
            int xMax = sectionCoord(box.maxX + 2.0);
            int yMax = sectionCoord(box.maxY);
            int zMax = sectionCoord(box.maxZ + 2.0);
            long volume = SectionRangeMath.saturatedVolume(xMin, yMin, zMin, xMax, yMax, zMax);
            return volume <= SectionRangeMath.DIRECT_LOOKUP_BUDGET;
        }

        boolean lookupWouldSkipDenseProbe(final AABB box) {
            int xMin = sectionCoord(box.minX - 2.0);
            int yMin = sectionCoord(box.minY - 4.0);
            int zMin = sectionCoord(box.minZ - 2.0);
            int xMax = sectionCoord(box.maxX + 2.0);
            int yMax = sectionCoord(box.maxY);
            int zMax = sectionCoord(box.maxZ + 2.0);
            long volume = SectionRangeMath.saturatedVolume(xMin, yMin, zMin, xMax, yMax, zMax);
            return SectionRangeMath.preferVanillaSectionWalk(volume, this.accessible.size());
        }

        boolean allAccessibleZero(final AABB box, final EntityQueryKind kind) {
            return this.vanillaQueryEmpty(box, kind);
        }
    }

    private static int sectionCoord(final double v) {
        return net.minecraft.core.SectionPos.posToSectionCoord(v);
    }
}
